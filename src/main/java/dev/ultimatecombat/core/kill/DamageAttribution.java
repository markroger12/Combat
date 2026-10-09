package dev.ultimatecombat.core.kill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the player responsible for damage, including indirect damage:
 * projectiles, tamed animals, TNT, end crystals, lingering/splash potions, lightning (channeling),
 * fire and lava placed by a player, bed and respawn-anchor explosions, poison and wither effects,
 * and burning caused by fire aspect / flame.
 */
public final class DamageAttribution implements Listener {

    private static final long BLOCK_SOURCE_TTL = 30_000;
    private static final long EXPLOSION_TTL = 10_000;
    private static final long CRYSTAL_TTL = 15_000;

    private record Source(UUID player, long expiresAt) {
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Location l) {
            return new BlockKey(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
    }

    private final Map<BlockKey, Source> lava = new ConcurrentHashMap<>();
    private final Map<BlockKey, Source> fire = new ConcurrentHashMap<>();
    private final Map<BlockKey, Source> explosives = new ConcurrentHashMap<>();
    private final Map<UUID, Source> crystals = new ConcurrentHashMap<>();
    private final Map<UUID, Source> burning = new ConcurrentHashMap<>();
    private final Map<UUID, Source> poisoned = new ConcurrentHashMap<>();
    private volatile boolean countPets = true;

    public void setCountPets(boolean countPets) {
        this.countPets = countPets;
    }

    // ---------------------------------------------------------------------------- resolution

    /**
     * @return the player responsible for the damage event, or {@code null}
     */
    public Player attacker(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        Player viaSource = fromDamageSource(event);
        if (viaSource != null && viaSource != victim) {
            return viaSource;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player p = resolve(byEntity.getDamager());
            if (p != null) {
                return p;
            }
        }
        return indirect(event);
    }

    private static Player fromDamageSource(EntityDamageEvent event) {
        try {
            Entity causing = event.getDamageSource().getCausingEntity();
            if (causing instanceof Player p) {
                return p;
            }
        } catch (NoSuchMethodError | RuntimeException ignored) {
            // older server without DamageSource
        }
        return null;
    }

    /**
     * Resolves the responsible player of an attacking entity.
     */
    public Player resolve(Entity damager) {
        if (damager == null) {
            return null;
        }
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
            if (shooter instanceof Entity e && e != damager) {
                return resolve(e);
            }
            return null;
        }
        if (damager instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            return source == null || source == damager ? null : resolve(source);
        }
        if (damager instanceof EnderCrystal crystal) {
            Source s = crystals.get(crystal.getUniqueId());
            return s != null && s.expiresAt() > System.currentTimeMillis() ? org.bukkit.Bukkit.getPlayer(s.player()) : null;
        }
        if (damager instanceof AreaEffectCloud cloud) {
            ProjectileSource source = cloud.getSource();
            return source instanceof Player p ? p : null;
        }
        if (damager instanceof Tameable tameable && countPets) {
            return tameable.getOwner() instanceof Player p ? p : null;
        }
        if (damager instanceof EvokerFangs fangs) {
            LivingEntity owner = fangs.getOwner();
            return owner instanceof Player p ? p : null;
        }
        if (damager instanceof LightningStrike lightning) {
            try {
                return lightning.getCausingPlayer();
            } catch (NoSuchMethodError e) {
                return null;
            }
        }
        return null;
    }

    private Player indirect(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        Location loc = victim.getLocation();
        long now = System.currentTimeMillis();
        return switch (event.getCause()) {
            case LAVA -> nearby(lava, loc, 2, now);
            case FIRE -> nearby(fire, loc, 1, now);
            case FIRE_TICK -> {
                Source s = burning.get(victim.getUniqueId());
                Player p = s != null && s.expiresAt() > now ? org.bukkit.Bukkit.getPlayer(s.player()) : null;
                yield p != null ? p : nearby(fire, loc, 1, now);
            }
            case BLOCK_EXPLOSION -> nearby(explosives, loc, 7, now);
            case POISON, WITHER -> {
                Source s = poisoned.get(victim.getUniqueId());
                yield s != null && s.expiresAt() > now ? org.bukkit.Bukkit.getPlayer(s.player()) : null;
            }
            default -> null;
        };
    }

    private Player nearby(Map<BlockKey, Source> map, Location loc, int radius, long now) {
        World world = loc.getWorld();
        if (world == null || map.isEmpty()) {
            return null;
        }
        UUID wid = world.getUID();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        Source best = null;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Source s = map.get(new BlockKey(wid, bx + x, by + y, bz + z));
                    if (s != null && s.expiresAt() > now && (best == null || s.expiresAt() > best.expiresAt())) {
                        best = s;
                    }
                }
            }
        }
        return best == null ? null : org.bukkit.Bukkit.getPlayer(best.player());
    }

    // ---------------------------------------------------------------------------- tracking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getBucket() == Material.LAVA_BUCKET) {
            lava.put(BlockKey.of(event.getBlockClicked().getRelative(event.getBlockFace()).getLocation()),
                    new Source(event.getPlayer().getUniqueId(), System.currentTimeMillis() + BLOCK_SOURCE_TTL));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player p = event.getPlayer();
        if (p == null && event.getIgnitingEntity() != null) {
            p = resolve(event.getIgnitingEntity());
        }
        if (p != null) {
            fire.put(BlockKey.of(event.getBlock().getLocation()), new Source(p.getUniqueId(), System.currentTimeMillis() + BLOCK_SOURCE_TTL));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (type == Material.FIRE || type == Material.SOUL_FIRE) {
            fire.put(BlockKey.of(event.getBlockPlaced().getLocation()),
                    new Source(event.getPlayer().getUniqueId(), System.currentTimeMillis() + BLOCK_SOURCE_TTL));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExplosiveInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        boolean bed = block.getType().name().endsWith("_BED") && block.getWorld().getEnvironment() != World.Environment.NORMAL;
        boolean anchor = block.getType() == Material.RESPAWN_ANCHOR && block.getWorld().getEnvironment() != World.Environment.NETHER
                && block.getBlockData() instanceof RespawnAnchor a && a.getCharges() > 0;
        if (bed || anchor) {
            explosives.put(BlockKey.of(block.getLocation()),
                    new Source(event.getPlayer().getUniqueId(), System.currentTimeMillis() + EXPLOSION_TTL));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof EnderCrystal crystal) {
            Player p = resolve(event.getDamager());
            if (p != null) {
                crystals.put(crystal.getUniqueId(), new Source(p.getUniqueId(), System.currentTimeMillis() + CRYSTAL_TTL));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        Player p = resolve(event.getCombuster());
        if (p != null && p != event.getEntity()) {
            long duration;
            try {
                duration = (long) (event.getDuration() * 1000L) + 2000L;
            } catch (NoSuchMethodError e) {
                duration = 10_000L; // pre-1.21 servers returned an int here
            }
            burning.put(event.getEntity().getUniqueId(), new Source(p.getUniqueId(), System.currentTimeMillis() + duration));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower)) {
            return;
        }
        long longest = 0;
        for (PotionEffect effect : event.getPotion().getEffects()) {
            PotionEffectType type = effect.getType();
            if (type.equals(PotionEffectType.POISON) || type.equals(PotionEffectType.WITHER)) {
                longest = Math.max(longest, effect.getDuration() * 50L);
            }
        }
        if (longest <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + longest + 1000;
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected != thrower) {
                poisoned.put(affected.getUniqueId(), new Source(thrower.getUniqueId(), until));
            }
        }
    }

    /**
     * Removes expired entries. Called periodically by the kill service.
     */
    public void purge() {
        long now = System.currentTimeMillis();
        for (Map<?, Source> map : java.util.List.<Map<?, Source>>of(lava, fire, explosives, crystals, burning, poisoned)) {
            Iterator<Source> it = map.values().iterator();
            while (it.hasNext()) {
                if (it.next().expiresAt() <= now) {
                    it.remove();
                }
            }
        }
    }

    public void clearPlayer(UUID uuid) {
        burning.remove(uuid);
        poisoned.remove(uuid);
    }
}
