package dev.ultimatecombat.modules.healthbars;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Bukkit;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opponent health displays (healthbars.yml): health below names, a boss bar for the current
 * opponent and an optional action bar.
 */
public final class HealthbarModule extends Module<HealthbarModule.Settings> {

    private static final String OBJECTIVE = "uc_health";

    public record Settings(boolean belowName, boolean belowNameHearts, boolean applyToCustomScoreboards, boolean bossbar,
                           long bossbarMillis, double range, boolean includeMobs, BarStyle style, BarColor highColor,
                           BarColor mediumColor, BarColor lowColor, double mediumBelow, double lowBelow, boolean actionbar,
                           String fullHeart, String halfHeart, String emptyHeart, int heartCount, WorldRules worlds) {
    }

    private static final class Bar {
        final BossBar bar;
        volatile WeakReference<LivingEntity> target = new WeakReference<>(null);
        volatile long until;

        Bar(BossBar bar) {
            this.bar = bar;
        }
    }

    private final Map<UUID, Bar> bars = new ConcurrentHashMap<>();
    private ScheduledTask ticker;

    public HealthbarModule(UltimateCombatPlugin plugin) {
        super(plugin, "healthbars", "healthbars.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.bool("below-name.enabled", true),
                r.choice("below-name.mode", "hearts", "hearts", "health").equals("hearts"),
                r.bool("below-name.custom-scoreboards", true),
                r.bool("bossbar.enabled", true),
                r.durationMillis("bossbar.duration-seconds", 5000, ConfigReader.TimeUnitHint.SECONDS, 500, 600_000),
                r.decimal("bossbar.range", 32, 1, 256),
                r.bool("bossbar.include-mobs", true),
                r.enumValue("bossbar.style", BarStyle.class, BarStyle.SEGMENTED_10),
                r.enumValue("bossbar.colors.high", BarColor.class, BarColor.GREEN),
                r.enumValue("bossbar.colors.medium", BarColor.class, BarColor.YELLOW),
                r.enumValue("bossbar.colors.low", BarColor.class, BarColor.RED),
                r.percent("bossbar.colors.medium-below-percent", 60),
                r.percent("bossbar.colors.low-below-percent", 30),
                r.bool("actionbar.enabled", false),
                r.string("symbols.full", "❤"),
                r.string("symbols.half", "❥"),
                r.string("symbols.empty", "♡"),
                r.integer("symbols.count", 10, 1, 40),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onEnable() {
        ticker = plugin.scheduler().runGlobalTimer(this::tick, 5L, 5L);
        warnFolia();
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().executeEntity(p, () -> setupScoreboard(p));
        }
    }

    @Override
    protected void onReload(Settings previous) {
        warnFolia();
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().executeEntity(p, () -> setupScoreboard(p));
        }
    }

    /**
     * Folia does not implement the scoreboard API, so below-name health is unavailable there.
     */
    private boolean scoreboardsSupported() {
        return !plugin.platform().isFolia();
    }

    private void warnFolia() {
        if (settings().belowName() && !scoreboardsSupported()) {
            plugin.getLogger().warning("[healthbars] below-name.enabled is true, but Folia does not support scoreboards. "
                    + "Below-name health is disabled; boss bar and action bar health still work.");
        }
    }

    @Override
    protected void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Bar bar : bars.values()) {
            bar.bar.removeAll();
        }
        bars.clear();
        if (!scoreboardsSupported()) {
            return;
        }
        removeObjective(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeObjective(p.getScoreboard());
        }
    }

    // ------------------------------------------------------------------------------ below name

    private static void removeObjective(Scoreboard board) {
        Objective o = board.getObjective(OBJECTIVE);
        if (o != null) {
            o.unregister();
        }
    }

    @SuppressWarnings("deprecation")
    private void setupScoreboard(Player player) {
        if (!scoreboardsSupported() || !player.isOnline()) {
            return;
        }
        Settings s = settings();
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Scoreboard board = player.getScoreboard();
        for (Scoreboard sb : board == main || !s.applyToCustomScoreboards() ? new Scoreboard[]{main} : new Scoreboard[]{main, board}) {
            if (!s.belowName()) {
                removeObjective(sb);
                continue;
            }
            Objective o = sb.getObjective(OBJECTIVE);
            String title = plugin.messages().text("healthbars.below-name");
            if (o == null) {
                o = s.belowNameHearts() ? sb.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title)
                        : sb.registerNewObjective(OBJECTIVE, Criteria.HEALTH, title);
            } else {
                o.setDisplayName(title);
            }
            o.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }
        updateScore(player);
    }

    private void updateScore(Player player) {
        Settings s = settings();
        if (!scoreboardsSupported() || !s.belowName() || !s.belowNameHearts() || !player.isOnline()) {
            return;
        }
        int hearts = (int) Math.ceil(player.getHealth() / 2.0);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        setScore(main, player.getName(), hearts);
        if (s.applyToCustomScoreboards()) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                Scoreboard board = viewer.getScoreboard();
                if (board != main) {
                    setScore(board, player.getName(), hearts);
                }
            }
        }
    }

    private static void setScore(Scoreboard board, String entry, int value) {
        Objective o = board.getObjective(OBJECTIVE);
        if (o != null && o.getTrackedCriteria() == Criteria.DUMMY) {
            o.getScore(entry).setScore(value);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.scheduler().runEntityLater(p, () -> setupScoreboard(p), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHealthChange(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            plugin.scheduler().runEntityLater(p, () -> updateScore(p), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player p) {
            plugin.scheduler().runEntityLater(p, () -> updateScore(p), 1L);
        }
    }

    // ------------------------------------------------------------------------------ boss bar

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld())) {
            return;
        }
        Player attacker = plugin.kills().attribution().resolve(event.getDamager());
        if (attacker != null && attacker != victim && (victim instanceof Player || s.includeMobs())) {
            show(attacker, victim, s);
        }
        if (victim instanceof Player pv && attacker != null && attacker != victim) {
            show(pv, attacker, s);
        } else if (victim instanceof Player pv && s.includeMobs() && event.getDamager() instanceof LivingEntity mob) {
            show(pv, mob, s);
        }
    }

    private void show(Player viewer, LivingEntity target, Settings s) {
        if (s.bossbar()) {
            Bar bar = bars.computeIfAbsent(viewer.getUniqueId(), k -> {
                Bar b = new Bar(Bukkit.createBossBar(" ", s.highColor(), s.style()));
                b.bar.addPlayer(viewer);
                return b;
            });
            bar.target = new WeakReference<>(target);
            bar.until = System.currentTimeMillis() + s.bossbarMillis();
            plugin.scheduler().executeEntity(viewer, () -> render(viewer, bar, target, s));
        }
        if (s.actionbar()) {
            plugin.scheduler().runEntityLater(viewer, () -> plugin.messages().actionBar(viewer, plugin.messages().text(viewer,
                    "healthbars.actionbar", placeholders(target, s))), 1L);
        }
    }

    private Object[] placeholders(LivingEntity target, Settings s) {
        double health = Math.max(0, target.getHealth());
        double max = maxHealth(target);
        return new Object[]{"name", KillService.entityName(target), "health", Numbers.twoDecimals(health), "max", Numbers.twoDecimals(max),
                "hearts", Numbers.twoDecimals(health / 2), "percent", Math.round(health / max * 100), "bar", hearts(health, max, s)};
    }

    private static double maxHealth(LivingEntity e) {
        var attr = Registries.maxHealth();
        AttributeInstance inst = attr == null ? null : e.getAttribute(attr);
        return inst == null ? 20.0 : Math.max(1, inst.getValue());
    }

    private String hearts(double health, double max, Settings s) {
        double perHeart = max / s.heartCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.heartCount(); i++) {
            double value = health - i * perHeart;
            if (value >= perHeart * 0.75) {
                sb.append(s.fullHeart());
            } else if (value >= perHeart * 0.25) {
                sb.append(s.halfHeart());
            } else {
                sb.append(s.emptyHeart());
            }
        }
        return sb.toString();
    }

    private void render(Player viewer, Bar bar, LivingEntity target, Settings s) {
        double max = maxHealth(target);
        double fraction = Math.max(0, Math.min(1, target.getHealth() / max));
        bar.bar.setProgress(fraction);
        bar.bar.setColor(fraction < s.lowBelow() ? s.lowColor() : fraction < s.mediumBelow() ? s.mediumColor() : s.highColor());
        bar.bar.setTitle(plugin.messages().text(viewer, "healthbars.bossbar", placeholders(target, s)));
    }

    private void tick() {
        for (Map.Entry<UUID, Bar> e : new ArrayList<>(bars.entrySet())) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            Bar bar = e.getValue();
            if (viewer == null) {
                bar.bar.removeAll();
                bars.remove(e.getKey(), bar);
                continue;
            }
            // runs on the viewer's thread (region thread on Folia)
            plugin.scheduler().executeEntity(viewer, () -> {
                Settings s = settings(viewer.getWorld());
                LivingEntity target = bar.target.get();
                boolean expired = !viewer.isOnline() || System.currentTimeMillis() > bar.until || target == null || target.isDead()
                        || !target.isValid() || !target.getWorld().equals(viewer.getWorld())
                        || target.getLocation().distanceSquared(viewer.getLocation()) > s.range() * s.range();
                if (expired) {
                    bar.bar.removeAll();
                    bars.remove(e.getKey(), bar);
                } else {
                    render(viewer, bar, target, s);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Bar bar = bars.remove(event.getPlayer().getUniqueId());
        if (bar != null) {
            bar.bar.removeAll();
        }
    }
}
