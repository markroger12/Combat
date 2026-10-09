package dev.ultimatecombat.modules.killstreaks;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatLogEvent;
import dev.ultimatecombat.api.event.KillstreakEvent;
import dev.ultimatecombat.core.action.ActionExecutor;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.player.PlayerStats;
import dev.ultimatecombat.modules.rewards.RewardsModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kill streaks (killstreaks.yml).
 */
public final class KillstreakModule extends Module<KillstreakModule.Settings> implements KillHandler {

    /**
     * A milestone or the repeating reward.
     */
    public record Milestone(boolean broadcast, boolean title, String sound, String particle, List<String> actions) {
    }

    public record Settings(boolean resetOnDeath, boolean resetOnCombatLog, TreeMap<Integer, Milestone> milestones, int repeatEvery,
                           Milestone repeating, int announceEndFrom) {
    }

    private boolean registeredHandler;

    public KillstreakModule(UltimateCombatPlugin plugin) {
        super(plugin, "killstreaks", "killstreaks.yml");
    }

    private static Milestone milestone(ConfigReader m) {
        return new Milestone(m.bool("broadcast", true), m.bool("title", false), m.string("sound", "killstreak"),
                m.string("particle", "killstreak"), ActionExecutor.validate(m, "actions", m.stringList("actions")));
    }

    @Override
    protected Settings parse(ConfigReader r) {
        TreeMap<Integer, Milestone> milestones = new TreeMap<>();
        for (String key : r.keys("milestones")) {
            try {
                int streak = Integer.parseInt(key.trim());
                if (streak <= 0) {
                    throw new NumberFormatException();
                }
                milestones.put(streak, milestone(r.section("milestones." + key)));
            } catch (NumberFormatException e) {
                r.warn("milestones." + key, key, "Milestone keys must be positive whole numbers (entry ignored)", "\"5\", \"10\" ...",
                        "entry skipped");
            }
        }
        return new Settings(
                r.bool("reset-on-death", true),
                r.bool("reset-on-combat-log", true),
                milestones,
                r.integer("repeating.every", 0, 0, 10_000),
                milestone(r.section("repeating")),
                r.integer("announce-end-from", 5, 0, 10_000));
    }

    @Override
    protected void onEnable() {
        if (!registeredHandler) {
            plugin.kills().register(this);
            registeredHandler = true;
        }
    }

    @Override
    public boolean activeHandler() {
        return isEnabled();
    }

    @Override
    public int killPriority() {
        return STREAKS;
    }

    @Override
    public void onPlayerDeath(KillContext ctx) {
        Player victim = ctx.victim();
        Settings s = settings(victim.getWorld());
        PlayerStats victimStats = plugin.players().profile(victim).stats();
        int before = victimStats.streak();
        ctx.victimStreakBefore(before);
        if (s.resetOnDeath() && (!ctx.combatLogDeath() || s.resetOnCombatLog())) {
            int old = victimStats.resetStreak();
            if (old > 0) {
                Bukkit.getPluginManager().callEvent(new KillstreakEvent(victim, old, 0));
                if (s.announceEndFrom() > 0 && old >= s.announceEndFrom()) {
                    plugin.messages().broadcast("killstreaks.ended", "player", victim.getName(), "streak", old,
                            "killer", ctx.killer() == null ? plugin.messages().settings().none() : ctx.killer().getName());
                }
            }
        }
        if (!ctx.isPvp() || ctx.statsSuppressed()) {
            return;
        }
        Player killer = ctx.killer();
        PlayerStats killerStats = plugin.players().profile(killer).stats();
        int previous = killerStats.streak();
        int streak = killerStats.incrementStreak();
        ctx.killerStreakAfter(streak);
        Bukkit.getPluginManager().callEvent(new KillstreakEvent(killer, previous, streak));
        if (ctx.rewardsSuppressed()) {
            return;
        }
        Settings ks = settings(killer.getWorld());
        Milestone milestone = ks.milestones().get(streak);
        if (milestone == null && ks.repeatEvery() > 0 && streak % ks.repeatEvery() == 0) {
            milestone = ks.repeating();
        }
        if (milestone != null) {
            reward(killer, streak, milestone);
        }
        RewardsModule rewards = plugin.active(RewardsModule.class);
        if (rewards != null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("victim", victim.getName());
            rewards.trigger(RewardsModule.Trigger.KILLSTREAK, killer, ph, streak, null, null);
        }
    }

    private void reward(Player player, int streak, Milestone m) {
        if (m.broadcast()) {
            plugin.messages().broadcast("killstreaks.reached", "player", player.getName(), "streak", streak);
        }
        if (m.title()) {
            plugin.messages().send(player, "killstreaks.title", "player", player.getName(), "streak", streak);
        }
        plugin.effects().playSound(player, m.sound());
        plugin.effects().spawnParticle(player.getLocation(), m.particle());
        Map<String, String> ph = new HashMap<>();
        ph.put("player", player.getName());
        ph.put("streak", Integer.toString(streak));
        plugin.actions().run(player, m.actions(), ph);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatLog(CombatLogEvent event) {
        Settings s = settings(event.getPlayer().getWorld());
        if (s.resetOnCombatLog()) {
            PlayerStats stats = plugin.players().profile(event.getPlayer()).stats();
            int old = stats.resetStreak();
            if (old > 0) {
                Bukkit.getPluginManager().callEvent(new KillstreakEvent(event.getPlayer(), old, 0));
            }
        }
    }
}
