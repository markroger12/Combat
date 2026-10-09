package dev.ultimatecombat.modules.death;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Locations;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.spawn.SpawnpointModule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Death handling (death.yml): automatic respawn, void rescue, death location tracking with
 * /deathback, and item spread.
 */
public final class DeathModule extends Module<DeathModule.Settings> {

    public record Settings(boolean autoRespawn, long autoRespawnDelayTicks, boolean disableItemSpread, boolean voidRescue,
                           boolean voidRescueOutOfCombatOnly, boolean deathLocationMessage, boolean deathbackEnabled,
                           long deathbackCooldownMillis, long deathbackExpireMillis, boolean deathbackBlockInCombat,
                           boolean deathbackPvpOnly, WorldRules worlds) {
    }

    private NamespacedKey deathKey;
    private final Map<UUID, Long> deathbackUsed = new ConcurrentHashMap<>();

    public DeathModule(UltimateCombatPlugin plugin) {
        super(plugin, "death", "death.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.bool("auto-respawn.enabled", false),
                r.ticks("auto-respawn.delay-ticks", 1, 1, 1200),
                r.bool("disable-item-spread", false),
                r.bool("void-rescue.enabled", false),
                r.bool("void-rescue.only-out-of-combat", true),
                r.bool("death-location-message", true),
                r.bool("deathback.enabled", true),
                r.durationMillis("deathback.cooldown-seconds", 60_000, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.durationMillis("deathback.expire-minutes", 10 * 60_000L, ConfigReader.TimeUnitHint.MINUTES, 0, 7L * 86_400_000L),
                r.bool("deathback.block-in-combat", true),
                r.bool("deathback.only-pvp-deaths", false),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onEnable() {
        deathKey = new NamespacedKey(plugin, "last_death");
    }

    @Override
    protected void onDisable() {
        deathbackUsed.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeathDrops(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Settings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld()) || !s.disableItemSpread() || event.getDrops().isEmpty()) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        Location loc = player.getLocation();
        for (ItemStack drop : drops) {
            if (drop != null && !drop.getType().isAir()) {
                player.getWorld().dropItem(loc, drop, item -> item.setVelocity(new org.bukkit.util.Vector(0, 0, 0)));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Settings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld())) {
            return;
        }
        boolean pvp = player.getKiller() != null;
        if (!s.deathbackPvpOnly() || pvp) {
            player.getPersistentDataContainer().set(deathKey, PersistentDataType.STRING,
                    System.currentTimeMillis() + "|" + Locations.encode(player.getLocation()));
        }
        if (s.deathLocationMessage()) {
            Location l = player.getLocation();
            plugin.messages().send(player, "death.location", "world", l.getWorld().getName(), "x", l.getBlockX(), "y", l.getBlockY(),
                    "z", l.getBlockZ());
        }
        if (s.autoRespawn()) {
            plugin.scheduler().runEntityLater(player, () -> {
                if (player.isDead()) {
                    player.spigot().respawn();
                }
            }, s.autoRespawnDelayTicks());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVoid(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (!s.voidRescue() || !s.worlds().allows(player.getWorld())) {
            return;
        }
        CombatModule combat = plugin.active(CombatModule.class);
        if (s.voidRescueOutOfCombatOnly() && combat != null && combat.isTagged(player)) {
            return;
        }
        event.setCancelled(true);
        player.setFallDistance(0);
        Location target = rescueTarget(player);
        plugin.scheduler().teleport(player, target);
        plugin.messages().send(player, "death.void-rescued");
    }

    private Location rescueTarget(Player player) {
        SpawnpointModule spawns = plugin.active(SpawnpointModule.class);
        if (spawns != null) {
            Location l = spawns.respawnLocation(player, player.getLocation());
            if (l != null) {
                return l;
            }
        }
        return player.getWorld().getSpawnLocation();
    }

    // ------------------------------------------------------------------------------ deathback

    @Override
    public void registerCommands() {
        SimpleCommand cmd = new SimpleCommand("deathback", "deathback", "ultimatecombat.deathback", true, this::deathback);
        plugin.commands().root().child(cmd);
        plugin.commands().standalone("deathback", cmd);
    }

    private void deathback(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Player player = ctx.player();
        Settings s = settings(player.getWorld());
        if (!s.deathbackEnabled()) {
            ctx.reply("death.deathback-disabled");
            return;
        }
        CombatModule combat = plugin.active(CombatModule.class);
        if (s.deathbackBlockInCombat() && combat != null && combat.isTagged(player) && !plugin.bypasses(player, "deathback")) {
            ctx.reply("death.deathback-in-combat");
            return;
        }
        String raw = player.getPersistentDataContainer().get(deathKey, PersistentDataType.STRING);
        if (raw == null || !raw.contains("|")) {
            ctx.reply("death.deathback-none");
            return;
        }
        long at = Long.parseLong(raw.substring(0, raw.indexOf('|')));
        Location loc = Locations.decode(raw.substring(raw.indexOf('|') + 1));
        if (loc == null || s.deathbackExpireMillis() > 0 && System.currentTimeMillis() - at > s.deathbackExpireMillis()) {
            ctx.reply("death.deathback-none");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = deathbackUsed.get(player.getUniqueId());
        if (last != null && now - last < s.deathbackCooldownMillis() && !plugin.bypasses(player, "deathback")) {
            ctx.reply("death.deathback-cooldown", "time", plugin.messages().duration(s.deathbackCooldownMillis() - (now - last)));
            return;
        }
        deathbackUsed.put(player.getUniqueId(), now);
        player.getPersistentDataContainer().remove(deathKey);
        plugin.scheduler().teleport(player, loc);
        ctx.reply("death.deathback-done", "world", loc.getWorld().getName(), "x", loc.getBlockX(), "y", loc.getBlockY(),
                "z", loc.getBlockZ());
    }
}
