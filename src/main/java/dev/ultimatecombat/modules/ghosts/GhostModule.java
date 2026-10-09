package dev.ultimatecombat.modules.ghosts;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.TagCause;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.NpcHook;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.pending.PendingActions;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combatlog.CombatLogSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ghost players (ghosts.yml): a killable stand-in that stays behind when a tagged player logs out.
 *
 * <p>Item safety: the logged-out player keeps their real inventory. Killing the ghost first
 * stores a persistent "clear inventory on rejoin" action; only after the database confirmed it
 * are copies of the items dropped. A crash before that point drops nothing (no duplication) and
 * the player keeps their items (no loss). If the player rejoins before the drop happens, the drop
 * is cancelled.</p>
 */
public final class GhostModule extends Module<GhostSettings> {

    static final String PENDING_KILLED = "GHOST_KILLED";
    private static final int ALIVE = 0;
    private static final int DYING = 1;
    private static final int GONE = 2;

    private final Map<UUID, Ghost> byOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Ghost> byEntity = new ConcurrentHashMap<>();
    private NamespacedKey key;
    private volatile boolean halted;

    private static final class Ghost {
        final UUID owner;
        final String ownerName;
        final ItemStack[] contents;
        final int level;
        final Location location;
        final AtomicInteger state = new AtomicInteger(ALIVE);
        volatile Entity entity;
        volatile NpcHook.Handle npc;
        volatile ScheduledTask expireTask;
        volatile CompletableFuture<Long> pendingId;

        Ghost(UUID owner, String ownerName, ItemStack[] contents, int level, Location location) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.contents = contents;
            this.level = level;
            this.location = location;
        }
    }

    public GhostModule(UltimateCombatPlugin plugin) {
        super(plugin, "ghost-players", "ghosts.yml");
    }

    @Override
    protected GhostSettings parse(ConfigReader reader) {
        return GhostSettings.parse(reader);
    }

    @Override
    protected void onEnable() {
        key = new NamespacedKey(plugin, "ghost_owner");
        plugin.pending().handler(PENDING_KILLED, this::applyKilled);
    }

    @Override
    protected void onDisable() {
        plugin.pending().removeHandler(PENDING_KILLED);
        for (Ghost ghost : new ArrayList<>(byOwner.values())) {
            int state = ghost.state.get();
            if (state == ALIVE && ghost.state.compareAndSet(ALIVE, GONE)) {
                despawn(ghost);
            } else if (state == DYING) {
                // The drop has not happened yet: cancel it so the owner simply keeps the items.
                ghost.state.set(GONE);
                CompletableFuture<Long> future = ghost.pendingId;
                if (future != null) {
                    try {
                        long id = future.get(2, TimeUnit.SECONDS);
                        plugin.pending().cancel(id).get(2, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not cancel ghost result of " + ghost.ownerName + ": " + Database.rootMessage(e));
                    }
                }
                despawn(ghost);
            }
        }
        byOwner.clear();
        byEntity.clear();
    }

    public boolean isGhost(Entity entity) {
        return byEntity.containsKey(entity.getUniqueId());
    }

    // ------------------------------------------------------------------------------------ spawning

    /**
     * Spawns a ghost for a player who is logging out. Must run on the player's thread (quit event).
     *
     * @return {@code true} when a ghost now represents the player
     */
    public boolean spawn(Player player, CombatLogSettings.Profile profile, Player opponent) {
        GhostSettings s = settings(player.getWorld());
        if (halted || !plugin.storageReady() || !s.worlds().allows(player.getWorld()) || byOwner.containsKey(player.getUniqueId())) {
            return false;
        }
        Location loc = player.getLocation();
        if (!s.excludedRegions().isEmpty()) {
            for (String region : plugin.integrations().regionsAt(loc)) {
                if (s.excludedRegions().contains(region)) {
                    return false;
                }
            }
        }
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] snapshot = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            snapshot[i] = contents[i] == null ? null : contents[i].clone();
        }
        Ghost ghost = new Ghost(player.getUniqueId(), player.getName(), snapshot, player.getLevel(), loc.clone());
        String name = plugin.messages().text(player, "ghosts.name", "player", player.getName());

        Entity entity = null;
        NpcHook npcs = plugin.integrations().npcs();
        boolean wantCitizens = s.npcType().equals("auto") || s.npcType().equals("citizens");
        if (wantCitizens && npcs != null) {
            try {
                NpcHook.Handle handle = npcs.spawn(player, loc, name, s.fightBack());
                if (handle != null && handle.entity() != null) {
                    ghost.npc = handle;
                    entity = handle.entity();
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.debug().log("combatlog", "Citizens ghost failed, using vanilla entity: " + e);
            }
        }
        if (entity == null) {
            try {
                entity = spawnVanilla(player, loc, s);
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("Could not spawn ghost player for " + player.getName() + ": " + e.getMessage());
                return false;
            }
        }
        if (entity == null) {
            return false;
        }
        configure(entity, player, s, name, ghost.npc == null);
        ghost.entity = entity;
        byOwner.put(ghost.owner, ghost);
        byEntity.put(entity.getUniqueId(), ghost);
        ghost.expireTask = plugin.scheduler().runEntityLater(entity, () -> expire(ghost), Math.max(1, s.durationMillis() / 50));
        plugin.messages().broadcast(p -> p.getWorld().equals(loc.getWorld()) && p.getLocation().distanceSquared(loc) < 64 * 64,
                "ghosts.spawned", "player", player.getName(), "time", s.durationMillis() / 1000);
        plugin.debug().log("combatlog", "Ghost spawned for " + player.getName() + " as " + entity.getType());
        return true;
    }

    private Entity spawnVanilla(Player player, Location loc, GhostSettings s) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }
        boolean mannequin = (s.npcType().equals("auto") || s.npcType().equals("mannequin")) && plugin.platform().isPaperBased()
                && MannequinBridge.available();
        if (mannequin) {
            return MannequinBridge.spawn(loc, player);
        }
        Class<? extends Entity> type = s.fallbackType().getEntityClass();
        if (type == null) {
            return null;
        }
        return world.spawn(loc, type);
    }

    @SuppressWarnings("deprecation")
    private void configure(Entity entity, Player player, GhostSettings s, String name, boolean vanilla) {
        entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
        entity.setPersistent(false);
        if (vanilla) {
            entity.setCustomName(name);
            entity.setCustomNameVisible(true);
        }
        entity.setGlowing(s.glowing());
        entity.setInvulnerable(!s.killable());
        if (entity instanceof LivingEntity living) {
            living.setRemoveWhenFarAway(false);
            if (vanilla) {
                living.setAI(s.ai());
                living.setCanPickupItems(false);
            }
            Attribute maxHealth = Registries.maxHealth();
            double health = s.usePlayerHealth() ? Math.max(1, player.getHealth()) : s.fixedHealth();
            if (maxHealth != null) {
                AttributeInstance attr = living.getAttribute(maxHealth);
                if (attr != null) {
                    attr.setBaseValue(Math.max(health, s.usePlayerHealth() && player.getAttribute(maxHealth) != null
                            ? player.getAttribute(maxHealth).getValue() : health));
                }
            }
            living.setHealth(Math.min(health, maxHealthOf(living, maxHealth)));
            EntityEquipment eq = living.getEquipment();
            if (eq != null && vanilla) {
                EntityEquipment src = player.getEquipment();
                if (s.showArmor() && src != null) {
                    eq.setHelmet(copy(src.getHelmet()));
                    eq.setChestplate(copy(src.getChestplate()));
                    eq.setLeggings(copy(src.getLeggings()));
                    eq.setBoots(copy(src.getBoots()));
                }
                if (s.showHeldItem() && src != null) {
                    eq.setItemInMainHand(copy(src.getItemInMainHand()));
                    eq.setItemInOffHand(copy(src.getItemInOffHand()));
                }
                eq.setHelmetDropChance(0f);
                eq.setChestplateDropChance(0f);
                eq.setLeggingsDropChance(0f);
                eq.setBootsDropChance(0f);
                eq.setItemInMainHandDropChance(0f);
                eq.setItemInOffHandDropChance(0f);
            }
        }
    }

    private static double maxHealthOf(LivingEntity living, Attribute maxHealth) {
        if (maxHealth == null) {
            return 20;
        }
        AttributeInstance attr = living.getAttribute(maxHealth);
        return attr == null ? 20 : attr.getValue();
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private void despawn(Ghost ghost) {
        ScheduledTask t = ghost.expireTask;
        if (t != null) {
            t.cancel();
        }
        Entity entity = ghost.entity;
        if (entity != null) {
            byEntity.remove(entity.getUniqueId());
            Runnable remove = () -> {
                NpcHook.Handle npc = ghost.npc;
                if (npc != null) {
                    npc.destroy();
                } else if (entity.isValid()) {
                    entity.remove();
                }
            };
            if (plugin.isEnabled()) {
                plugin.scheduler().executeEntity(entity, remove);
            } else {
                remove.run();
            }
        }
        byOwner.remove(ghost.owner, ghost);
    }

    private void expire(Ghost ghost) {
        if (ghost.state.compareAndSet(ALIVE, GONE)) {
            despawn(ghost);
            plugin.debug().log("combatlog", "Ghost of " + ghost.ownerName + " expired (owner keeps the items)");
        }
    }

    // ------------------------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Ghost ghost = byEntity.get(event.getEntity().getUniqueId());
        if (ghost == null) {
            return;
        }
        GhostSettings s = settings(event.getEntity().getWorld());
        if (!s.killable()) {
            event.setCancelled(true);
            return;
        }
        Player attacker = plugin.kills().attribution().resolve(event.getDamager());
        CombatModule combat = plugin.active(CombatModule.class);
        if (attacker != null && combat != null) {
            plugin.scheduler().executeEntity(attacker, () -> combat.tag(attacker, event.getEntity(), CombatType.PVP, TagCause.ATTACKER, 0));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnvironmentDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        Ghost ghost = byEntity.get(event.getEntity().getUniqueId());
        if (ghost != null && !settings(event.getEntity().getWorld()).killable()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getEntity() instanceof Mob && byEntity.containsKey(event.getEntity().getUniqueId())
                && !settings(event.getEntity().getWorld()).fightBack()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Ghost ghost = byEntity.get(event.getEntity().getUniqueId());
        if (ghost == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (!ghost.state.compareAndSet(ALIVE, DYING)) {
            return;
        }
        ScheduledTask t = ghost.expireTask;
        if (t != null) {
            t.cancel();
        }
        byEntity.remove(event.getEntity().getUniqueId());
        Player killer = event.getEntity().getKiller();
        if (killer == null && event.getEntity().getLastDamageCause() != null) {
            killer = plugin.kills().attribution().attacker(event.getEntity().getLastDamageCause());
        }
        Player finalKiller = killer;
        GhostSettings s = settings(event.getEntity().getWorld());
        Location dropAt = event.getEntity().getLocation();
        String payload = (finalKiller == null ? "" : finalKiller.getName()) + "|" + System.currentTimeMillis();
        CompletableFuture<Long> future = plugin.pending().add(ghost.owner, PENDING_KILLED, payload);
        ghost.pendingId = future;
        future.whenComplete((id, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Ghost result of " + ghost.ownerName + " could not be stored (" + Database.rootMessage(error)
                        + "). Nothing is dropped and the player keeps their items.");
                ghost.state.set(GONE);
                byOwner.remove(ghost.owner, ghost);
                return;
            }
            plugin.scheduler().runAt(dropAt, () -> finishDeath(ghost, id, finalKiller, dropAt, s));
        });
        NpcHook.Handle npc = ghost.npc;
        if (npc != null) {
            plugin.scheduler().runGlobalLater(npc::destroy, 2L);
        }
    }

    private void finishDeath(Ghost ghost, long pendingId, Player killer, Location at, GhostSettings s) {
        if (Bukkit.getPlayer(ghost.owner) != null || !ghost.state.compareAndSet(DYING, GONE)) {
            // The owner came back before the result was final: cancel, the owner keeps everything.
            plugin.pending().cancel(pendingId);
            ghost.state.set(GONE);
            byOwner.remove(ghost.owner, ghost);
            return;
        }
        byOwner.remove(ghost.owner, ghost);
        World world = at.getWorld();
        if (world != null) {
            if (s.dropInventory()) {
                for (ItemStack item : ghost.contents) {
                    if (item != null && !item.getType().isAir()) {
                        world.dropItemNaturally(at, item);
                    }
                }
            }
            int xp = s.dropExperience() ? Math.min(ghost.level * 7, 100) : 0;
            if (xp > 0) {
                world.spawn(at, ExperienceOrb.class, orb -> orb.setExperience(xp));
            }
        }
        if (s.countAsDeath()) {
            plugin.players().modify(ghost.owner, p -> {
                p.stats().addDeath();
                p.stats().resetStreak();
            });
        }
        if (killer != null && s.creditKiller()) {
            plugin.players().profile(killer).stats().addKill();
        }
        if (s.broadcastKill()) {
            plugin.messages().broadcast("ghosts.killed", "player", ghost.ownerName,
                    "killer", killer == null ? plugin.messages().settings().none() : killer.getName());
        }
        plugin.debug().log("combatlog", "Ghost of " + ghost.ownerName + " killed; items dropped, inventory cleared on rejoin");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Ghost ghost = byOwner.get(event.getPlayer().getUniqueId());
        if (ghost == null) {
            return;
        }
        if (settings(event.getPlayer().getWorld()).removeOnRejoin() && ghost.state.compareAndSet(ALIVE, GONE)) {
            despawn(ghost);
            plugin.messages().send(event.getPlayer(), "ghosts.survived");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            Ghost ghost = byEntity.get(e.getUniqueId());
            if (ghost != null && ghost.state.compareAndSet(ALIVE, GONE)) {
                despawn(ghost);
            }
        }
    }

    private void applyKilled(Player player, PendingActions.Action action) {
        String[] parts = action.payload().split("\\|", -1);
        String killer = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : plugin.messages().settings().none();
        player.getInventory().clear();
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        plugin.messages().send(player, "ghosts.rejoin-killed", "killer", killer);
        if (settings(player.getWorld()).killOwnerOnRejoin() && !player.isDead()) {
            plugin.kills().markCombatLogDeath(player.getUniqueId(), false, false);
            player.setHealth(0.0);
        }
    }

    // ------------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        UCCommand group = new UCCommand("ghosts", "ultimatecombat.ghosts.admin", false) {
            @Override
            public String usage() {
                return "ghosts <list|remove|halt|resume>";
            }
        };
        group.child(new SimpleCommand("list", "list", "ultimatecombat.ghosts.admin", false, this::list));
        group.child(new SimpleCommand("remove", "remove <player>", "ultimatecombat.ghosts.admin", false, this::remove,
                ctx -> byOwner.values().stream().map(g -> g.ownerName).toList()));
        group.child(new SimpleCommand("halt", "halt", "ultimatecombat.ghosts.admin", false, ctx -> {
            halted = true;
            ctx.reply("ghosts.halted");
        }));
        group.child(new SimpleCommand("resume", "resume", "ultimatecombat.ghosts.admin", false, ctx -> {
            halted = false;
            ctx.reply("ghosts.resumed");
        }));
        plugin.commands().root().child(group);
    }

    private void list(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        List<Ghost> ghosts = new ArrayList<>(byOwner.values());
        ctx.reply("ghosts.list-header", "count", ghosts.size(), "halted", plugin.messages().bool(halted));
        for (Ghost g : ghosts) {
            ctx.reply("ghosts.list-entry", "player", g.ownerName, "world", g.location.getWorld() == null ? "?" : g.location.getWorld().getName(),
                    "x", g.location.getBlockX(), "y", g.location.getBlockY(), "z", g.location.getBlockZ());
        }
    }

    private void remove(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 1) {
            ctx.reply("general.usage", "usage", "/uc ghosts remove <player>");
            return;
        }
        for (Ghost g : new ArrayList<>(byOwner.values())) {
            if (g.ownerName.equalsIgnoreCase(ctx.arg(0)) && g.state.compareAndSet(ALIVE, GONE)) {
                despawn(g);
                ctx.reply("ghosts.removed", "player", g.ownerName);
                return;
            }
        }
        ctx.reply("ghosts.not-found", "player", ctx.arg(0));
    }
}
