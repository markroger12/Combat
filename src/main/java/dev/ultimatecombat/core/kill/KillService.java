package dev.ultimatecombat.core.kill;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatKillEvent;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Central kill pipeline: records damage, decides kill credit and assists, and runs every
 * registered {@link KillHandler} in a fixed order for each death. Combat-log deaths are flagged so
 * reward systems can ignore them.
 */
public final class KillService implements Listener {

    private final UltimateCombatPlugin plugin;
    private final DamageAttribution attribution = new DamageAttribution();
    private final DamageTracker tracker = new DamageTracker();
    private final List<KillHandler> handlers = new CopyOnWriteArrayList<>();
    private final Map<UUID, int[]> combatLogDeaths = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> lastWeapon = new ConcurrentHashMap<>();
    private volatile Settings settings = new Settings("last-hit", 15_000, true, true, 15_000, 2.0, 0.1);
    private ScheduledTask purgeTask;
    private boolean registeredAttribution;

    /**
     * Kill credit options (stats.yml: kill-credit).
     */
    public record Settings(String mode, long windowMillis, boolean creditEnvironmentDeaths, boolean assistsEnabled,
                           long assistWindowMillis, double assistMinDamage, double assistMinFraction) {
    }

    public KillService(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public DamageAttribution attribution() {
        return attribution;
    }

    public DamageTracker tracker() {
        return tracker;
    }

    public Settings settings() {
        return settings;
    }

    public void load() {
        ConfigReader r = plugin.configs().file("stats.yml").reader().section("kill-credit");
        settings = new Settings(
                r.choice("mode", "last-hit", "last-hit", "most-damage"),
                r.durationMillis("window-seconds", 15_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 600_000),
                r.bool("credit-environment-deaths", true),
                r.bool("assists.enabled", true),
                r.durationMillis("assists.window-seconds", 15_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 600_000),
                r.decimal("assists.min-damage", 2.0, 0, 10_000),
                r.percent("assists.min-percent", 10));
        attribution.setCountPets(plugin.configs().file("combat.yml").reader().bool("attribution.count-tamed-animals", true));
        tracker.setRetention(Math.max(settings.windowMillis(), 300_000));
        if (!registeredAttribution) {
            Bukkit.getPluginManager().registerEvents(attribution, plugin);
            registeredAttribution = true;
        }
        if (purgeTask == null) {
            purgeTask = plugin.scheduler().runAsyncTimer(() -> {
                attribution.purge();
                tracker.purge();
            }, 600L, 600L);
        }
    }

    public void register(KillHandler handler) {
        handlers.add(handler);
        List<KillHandler> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator.comparingInt(KillHandler::killPriority));
        handlers.clear();
        handlers.addAll(sorted);
    }

    /**
     * Flags the next death of this player as a combat-log punishment.
     *
     * @param creditKill whether the last opponent receives the kill
     * @param countStats whether the death counts in statistics
     */
    public void markCombatLogDeath(UUID player, boolean creditKill, boolean countStats) {
        combatLogDeaths.put(player, new int[]{creditKill ? 1 : 0, countStats ? 1 : 0});
    }

    public boolean isCombatLogDeathPending(UUID player) {
        return combatLogDeaths.containsKey(player);
    }

    // ------------------------------------------------------------------------------ damage

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof Player victim) || victim.hasMetadata("NPC")) {
            return;
        }
        if (event.getFinalDamage() <= 0) {
            return;
        }
        Player attacker = attribution.attacker(event);
        String source;
        String weapon = "";
        if (attacker != null) {
            source = attacker.getName();
            Entity direct = event instanceof EntityDamageByEntityEvent e ? e.getDamager() : null;
            if (direct == attacker) {
                ItemStack hand = attacker.getInventory().getItemInMainHand();
                weapon = itemName(hand);
                lastWeapon.put(attacker.getUniqueId(), hand.clone());
            } else if (direct instanceof Projectile projectile) {
                weapon = entityName(projectile);
                if (plugin.scheduler().isOwnedByCurrentThread(attacker)) {
                    lastWeapon.put(attacker.getUniqueId(), attacker.getInventory().getItemInMainHand().clone());
                }
            } else if (direct != null) {
                weapon = entityName(direct);
            } else {
                weapon = event.getCause().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            }
        } else if (event instanceof EntityDamageByEntityEvent e) {
            source = entityName(e.getDamager());
            weapon = source;
        } else {
            source = event.getCause().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        boolean critical = false;
        if (event instanceof EntityDamageByEntityEvent e) {
            try {
                critical = e.isCritical();
            } catch (NoSuchMethodError ignored) {
                // Spigot: unknown
            }
        }
        if (attacker == victim) {
            attacker = null;
        }
        tracker.record(victim.getUniqueId(), new DamageTracker.Hit(attacker == null ? null : attacker.getUniqueId(), source,
                event.getFinalDamage(), System.currentTimeMillis(), weapon, event.getCause(), critical));
    }

    public static String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            @SuppressWarnings("deprecation")
            String name = meta.getDisplayName();
            return name;
        }
        return pretty(item.getType().name());
    }

    public static String entityName(Entity entity) {
        if (entity == null) {
            return "";
        }
        if (entity instanceof Player p) {
            return p.getName();
        }
        @SuppressWarnings("deprecation")
        String custom = entity.getCustomName();
        if (custom != null && !custom.isEmpty()) {
            return Text.strip(custom);
        }
        return pretty(entity.getType().name());
    }

    public static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------ deaths

    /**
     * Determines the credited killer of a victim using the configured kill-credit rules.
     */
    public Player creditedKiller(Player victim, EntityDamageEvent lastDamage) {
        Settings s = settings;
        Player vanilla = victim.getKiller();
        if (s.mode().equals("most-damage")) {
            Map<UUID, Double> totals = tracker.damageByPlayer(victim.getUniqueId(), s.windowMillis());
            for (UUID id : totals.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p != victim) {
                    return p;
                }
            }
        }
        if (lastDamage != null) {
            Player direct = attribution.attacker(lastDamage);
            if (direct != null && direct != victim) {
                return direct;
            }
        }
        if (vanilla != null && vanilla != victim) {
            return vanilla;
        }
        if (s.creditEnvironmentDeaths()) {
            UUID last = tracker.lastPlayerAttacker(victim.getUniqueId(), s.windowMillis());
            if (last != null) {
                Player p = Bukkit.getPlayer(last);
                if (p != null && p != victim) {
                    return p;
                }
            }
        }
        return null;
    }

    public Set<UUID> assists(Player victim, Player killer) {
        Settings s = settings;
        Set<UUID> out = new LinkedHashSet<>();
        if (!s.assistsEnabled()) {
            return out;
        }
        Map<UUID, Double> totals = tracker.damageByPlayer(victim.getUniqueId(), s.assistWindowMillis());
        double sum = totals.values().stream().mapToDouble(Double::doubleValue).sum();
        for (Map.Entry<UUID, Double> e : totals.entrySet()) {
            if (killer != null && e.getKey().equals(killer.getUniqueId())) {
                continue;
            }
            if (e.getKey().equals(victim.getUniqueId())) {
                continue;
            }
            if (e.getValue() >= s.assistMinDamage() && (sum <= 0 || e.getValue() / sum >= s.assistMinFraction())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim.hasMetadata("NPC")) {
            return; // Citizens NPCs (including ghost players) are not real players
        }
        int[] flags = combatLogDeaths.remove(victim.getUniqueId());
        boolean combatLog = flags != null;
        Player killer = combatLog && flags[0] == 0 ? null : creditedKiller(victim, victim.getLastDamageCause());
        EntityDamageEvent.DamageCause cause = victim.getLastDamageCause() == null
                ? EntityDamageEvent.DamageCause.CUSTOM : victim.getLastDamageCause().getCause();
        process(victim, killer, cause, event, combatLog, combatLog && flags[1] == 0);
    }

    /**
     * Runs the pipeline for a combat-log punishment that did not produce a real death event.
     */
    public void processSynthetic(Player victim, Player opponent, boolean creditKill) {
        Player killer = creditKill ? (opponent != null ? opponent : creditedKiller(victim, null)) : null;
        process(victim, killer, EntityDamageEvent.DamageCause.CUSTOM, null, true, false);
    }

    private void process(Player victim, Player killer, EntityDamageEvent.DamageCause cause, PlayerDeathEvent event, boolean combatLog,
                         boolean skipStats) {
        Settings s = settings;
        Set<UUID> assists = assists(victim, killer);
        Map<UUID, Double> damage = tracker.damageByPlayer(victim.getUniqueId(), Math.max(s.windowMillis(), s.assistWindowMillis()));
        ItemStack weapon = killer == null ? null : lastWeapon.get(killer.getUniqueId());
        long fightStart = tracker.firstHitTime(victim.getUniqueId(), 60_000);
        Location location = victim.getLocation();
        KillContext ctx = new KillContext(victim, killer, assists, damage, cause, weapon, location, event, combatLog, fightStart);
        if (combatLog) {
            ctx.suppressRewards("combat-log");
        }
        if (skipStats) {
            ctx.suppressStats("combat-log");
        }
        for (KillHandler handler : handlers) {
            if (!handler.activeHandler()) {
                continue;
            }
            try {
                handler.onPlayerDeath(ctx);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Kill handler " + handler.getClass().getSimpleName() + " failed", e);
            }
        }
        Bukkit.getPluginManager().callEvent(new CombatKillEvent(victim, ctx.killer(), assists, combatLog,
                !ctx.rewardsSuppressed(), ctx.suppressionReason()));
        tracker.clear(victim.getUniqueId());
        attribution.clearPlayer(victim.getUniqueId());
        plugin.debug().log("kills", victim.getName() + " died (" + cause + "), killer=" + (killer == null ? "-" : killer.getName())
                + ", assists=" + assists.size() + ", combatLog=" + combatLog + ", suppressed=" + ctx.suppressionReason());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null) {
            return;
        }
        for (KillHandler handler : handlers) {
            if (!handler.activeHandler()) {
                continue;
            }
            try {
                handler.onMobKill(killer, entity, event);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Kill handler " + handler.getClass().getSimpleName() + " failed", e);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastWeapon.remove(event.getPlayer().getUniqueId());
    }
}
