package dev.ultimatecombat.core.cooldown;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.util.Cooldowns;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared cooldown tracking and display for every feature that limits how often something may be
 * used (restriction rules, golden apples, potions, totems, PvP toggling ...). How cooldowns are
 * shown is configured once in cooldowns.yml; how long each cooldown lasts is configured by the
 * owning feature.
 */
public final class CooldownService implements Listener {

    private final UltimateCombatPlugin plugin;
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, Map<String, Pending>> pendingReady = new ConcurrentHashMap<>();
    private volatile Settings settings = new Settings(true, true, true, true, "cooldown-ready", false, true);
    private ScheduledTask readyTask;

    private record Pending(long readyAt, String display, Material material) {
    }

    /**
     * cooldowns.yml snapshot.
     */
    public record Settings(boolean actionbarOnBlockedUse, boolean vanillaOverlay, boolean notifyReady, boolean readyActionbar,
                           String readySound, boolean resetOnDeath, boolean keepAfterLogout) {
    }

    public CooldownService(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ConfigReader r = plugin.configs().file("cooldowns.yml").reader();
        settings = new Settings(
                r.bool("actionbar-on-blocked-use", true),
                r.bool("vanilla-item-overlay", true),
                r.bool("ready-notification.enabled", true),
                r.bool("ready-notification.actionbar", true),
                r.string("ready-notification.sound", "cooldown-ready"),
                r.bool("reset-on-death", false),
                r.bool("keep-after-logout", true));
        if (readyTask == null) {
            readyTask = plugin.scheduler().runGlobalTimer(this::checkReady, 10L, 10L);
        }
    }

    public Settings settings() {
        return settings;
    }

    /**
     * @return remaining milliseconds of a cooldown (0 = ready)
     */
    public long remaining(Player player, String key) {
        return cooldowns.remaining(player.getUniqueId(), key);
    }

    public long remaining(UUID player, String key) {
        return cooldowns.remaining(player, key);
    }

    /**
     * Starts a cooldown.
     *
     * @param display  readable name for messages (for example "Ender Pearl")
     * @param material item to show the vanilla cooldown overlay on, or {@code null}
     */
    public void start(Player player, String key, long millis, String display, Material material) {
        if (millis <= 0) {
            return;
        }
        cooldowns.set(player.getUniqueId(), key, millis);
        Settings s = settings;
        if (material != null && s.vanillaOverlay()) {
            int ticks = (int) Math.min(Integer.MAX_VALUE, millis / 50);
            plugin.scheduler().executeEntity(player, () -> player.setCooldown(material, ticks));
        }
        if (s.notifyReady()) {
            pendingReady.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                    .put(key, new Pending(System.currentTimeMillis() + millis, display, material));
        }
    }

    public void clear(Player player, String key) {
        cooldowns.clear(player.getUniqueId(), key);
        Map<String, Pending> map = pendingReady.get(player.getUniqueId());
        if (map != null) {
            map.remove(key);
        }
    }

    /**
     * Checks a cooldown and tells the player how long is left when it is still running.
     *
     * @return {@code true} if the cooldown is over (the action may proceed)
     */
    public boolean ready(Player player, String key, String display) {
        long left = remaining(player, key);
        if (left <= 0) {
            return true;
        }
        String time = Text.formatSeconds(left, true);
        plugin.messages().send(player, "cooldowns.active", "item", display, "time", time);
        if (settings.actionbarOnBlockedUse()) {
            plugin.messages().actionBar(player, plugin.messages().text(player, "cooldowns.actionbar", "item", display, "time", time));
        }
        return false;
    }

    private void checkReady() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Pending>> entry : pendingReady.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            entry.getValue().entrySet().removeIf(e -> {
                if (e.getValue().readyAt() > now) {
                    return false;
                }
                if (p != null && cooldowns.remaining(p.getUniqueId(), e.getKey()) <= 0) {
                    Pending pending = e.getValue();
                    plugin.scheduler().executeEntity(p, () -> notifyReady(p, pending));
                }
                return true;
            });
        }
        pendingReady.values().removeIf(Map::isEmpty);
        cooldowns.purgeExpired();
    }

    private void notifyReady(Player player, Pending pending) {
        Settings s = settings;
        plugin.messages().send(player, "cooldowns.ready", "item", pending.display());
        if (s.readyActionbar()) {
            plugin.messages().actionBar(player, plugin.messages().text(player, "cooldowns.ready-actionbar", "item", pending.display()));
        }
        plugin.effects().playSound(player, s.readySound());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (settings.resetOnDeath()) {
            cooldowns.clear(event.getEntity().getUniqueId());
            pendingReady.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (!settings.keepAfterLogout()) {
            cooldowns.clear(event.getPlayer().getUniqueId());
        }
        pendingReady.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        if (readyTask != null) {
            readyTask.cancel();
            readyTask = null;
        }
    }
}
