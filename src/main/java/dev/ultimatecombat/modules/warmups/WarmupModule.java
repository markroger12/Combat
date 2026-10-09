package dev.ultimatecombat.modules.warmups;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatTagEvent;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command warmups and cooldowns (warmups.yml).
 */
public final class WarmupModule extends Module<WarmupModule.Settings> {

    public record CommandRule(String command, long warmupMillis, long cooldownMillis, boolean cancelOnMove, boolean cancelOnDamage,
                              boolean cancelOnCombat) {
    }

    public record Settings(Map<String, CommandRule> commands, boolean actionbar, boolean bossbar, BarColor barColor, double moveTolerance,
                           String tickSound, String cancelSound, String doneSound, WorldRules worlds) {
    }

    private static final class Pending {
        final String command;
        final CommandRule rule;
        final Location start;
        final long endsAt;
        volatile ScheduledTask task;
        volatile BossBar bar;

        Pending(String command, CommandRule rule, Location start) {
            this.command = command;
            this.rule = rule;
            this.start = start;
            this.endsAt = System.currentTimeMillis() + rule.warmupMillis();
        }
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> passThrough = ConcurrentHashMap.newKeySet();

    public WarmupModule(UltimateCombatPlugin plugin) {
        super(plugin, "command-warmups", "warmups.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        Map<String, CommandRule> commands = new LinkedHashMap<>();
        for (String key : r.keys("commands")) {
            ConfigReader c = r.section("commands." + key);
            String cmd = key.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
            commands.put(cmd, new CommandRule(cmd,
                    c.durationMillis("warmup-seconds", 5000, ConfigReader.TimeUnitHint.SECONDS, 0, 600_000),
                    c.durationMillis("cooldown-seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                    c.bool("cancel-on-move", true),
                    c.bool("cancel-on-damage", true),
                    c.bool("cancel-on-combat", true)));
        }
        return new Settings(commands,
                r.bool("display.actionbar", true),
                r.bool("display.bossbar", false),
                r.enumValue("display.bossbar-color", BarColor.class, BarColor.BLUE),
                r.decimal("move-tolerance", 0.5, 0, 10),
                r.string("sounds.tick", "warmup-tick"),
                r.string("sounds.cancelled", "warmup-cancelled"),
                r.string("sounds.done", "warmup-done"),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onDisable() {
        for (UUID id : new ArrayList<>(pending.keySet())) {
            Player p = Bukkit.getPlayer(id);
            cancel(id, p, null);
        }
        passThrough.clear();
    }

    private CommandRule match(String message, Settings s) {
        String typed = message.toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim();
        String label = typed.contains(" ") ? typed.substring(0, typed.indexOf(' ')) : typed;
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        CommandRule rule = s.commands().get(label);
        if (rule != null) {
            return rule;
        }
        try {
            Command cmd = Bukkit.getCommandMap().getCommand(label);
            if (cmd != null) {
                rule = s.commands().get(cmd.getName().toLowerCase(Locale.ROOT));
                if (rule != null) {
                    return rule;
                }
                for (String alias : cmd.getAliases()) {
                    rule = s.commands().get(alias.toLowerCase(Locale.ROOT));
                    if (rule != null) {
                        return rule;
                    }
                }
            }
        } catch (RuntimeException | NoSuchMethodError ignored) {
            // no command map
        }
        return null;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (passThrough.remove(player.getUniqueId())) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld())) {
            return;
        }
        CommandRule rule = match(event.getMessage(), s);
        if (rule == null || plugin.bypasses(player, "warmups")) {
            return;
        }
        String key = "warmup:" + rule.command();
        if (rule.cooldownMillis() > 0 && plugin.cooldowns().remaining(player, key) > 0) {
            event.setCancelled(true);
            plugin.cooldowns().ready(player, key, "/" + rule.command());
            return;
        }
        if (rule.warmupMillis() <= 0) {
            if (rule.cooldownMillis() > 0) {
                plugin.cooldowns().start(player, key, rule.cooldownMillis(), "/" + rule.command(), null);
            }
            return;
        }
        event.setCancelled(true);
        cancel(player.getUniqueId(), player, null);
        Pending p = new Pending(event.getMessage().substring(1), rule, player.getLocation());
        pending.put(player.getUniqueId(), p);
        if (s.bossbar()) {
            p.bar = Bukkit.createBossBar(" ", s.barColor(), BarStyle.SOLID);
            p.bar.addPlayer(player);
        }
        plugin.messages().send(player, "warmups.started", "command", "/" + rule.command(), "time", rule.warmupMillis() / 1000.0);
        p.task = plugin.scheduler().runEntityTimer(player, () -> tick(player, p), 1L, 2L);
    }

    private void tick(Player player, Pending p) {
        if (pending.get(player.getUniqueId()) != p) {
            if (p.task != null) {
                p.task.cancel();
            }
            return;
        }
        long left = p.endsAt - System.currentTimeMillis();
        Settings s = settings(player.getWorld());
        if (left <= 0) {
            pending.remove(player.getUniqueId());
            p.task.cancel();
            removeBar(p);
            passThrough.add(player.getUniqueId());
            if (p.rule.cooldownMillis() > 0) {
                plugin.cooldowns().start(player, "warmup:" + p.rule.command(), p.rule.cooldownMillis(), "/" + p.rule.command(), null);
            }
            plugin.effects().playSound(player, s.doneSound());
            player.performCommand(p.command);
            passThrough.remove(player.getUniqueId());
            return;
        }
        String time = Text.formatSeconds(left, true);
        if (s.actionbar()) {
            plugin.messages().actionBar(player, plugin.messages().text(player, "warmups.actionbar", "command", "/" + p.rule.command(),
                    "time", time));
        }
        if (p.bar != null) {
            p.bar.setTitle(plugin.messages().text(player, "warmups.bossbar", "command", "/" + p.rule.command(), "time", time));
            p.bar.setProgress(Math.max(0, Math.min(1, (double) left / Math.max(1, p.rule.warmupMillis()))));
        }
        if ((left / 1000) != ((left + 100) / 1000)) {
            plugin.effects().playSound(player, s.tickSound());
        }
    }

    private static void removeBar(Pending p) {
        if (p.bar != null) {
            p.bar.removeAll();
            p.bar = null;
        }
    }

    private void cancel(UUID id, Player player, String reasonKey) {
        Pending p = pending.remove(id);
        if (p == null) {
            return;
        }
        if (p.task != null) {
            p.task.cancel();
        }
        removeBar(p);
        if (player != null && reasonKey != null) {
            plugin.messages().send(player, reasonKey, "command", "/" + p.rule.command());
            plugin.effects().playSound(player, settings(player.getWorld()).cancelSound());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p == null || !p.rule.cancelOnMove() || event.getTo() == null) {
            return;
        }
        double tolerance = settings(event.getPlayer().getWorld()).moveTolerance();
        if (event.getTo().getWorld() != p.start.getWorld() || event.getTo().distanceSquared(p.start) > tolerance * tolerance) {
            cancel(event.getPlayer().getUniqueId(), event.getPlayer(), "warmups.cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            Pending p = pending.get(player.getUniqueId());
            if (p != null && p.rule.cancelOnDamage()) {
                cancel(player.getUniqueId(), player, "warmups.cancelled-damage");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p != null && p.rule.cancelOnCombat()) {
            Player player = event.getPlayer();
            plugin.scheduler().executeEntity(player, () -> cancel(player.getUniqueId(), player, "warmups.cancelled-combat"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), null, null);
        passThrough.remove(event.getPlayer().getUniqueId());
    }
}
