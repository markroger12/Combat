package dev.ultimatecombat.modules.stats;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatLogEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.player.PlayerProfile;
import dev.ultimatecombat.core.player.PlayerStats;
import dev.ultimatecombat.core.util.Numbers;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PvP statistics, points and rank names (stats.yml).
 */
public final class StatsModule extends Module<StatsModule.Settings> implements KillHandler {

    /**
     * stats.yml snapshot.
     */
    public record Settings(boolean countKills, boolean countDeaths, boolean countAssists, boolean countMobKills, boolean countCombatLogs,
                           boolean trackDamage, boolean pvpDeathsOnly, PointsMode pointsMode, long killPoints, long deathPoints,
                           long assistPoints, long combatLogPoints, long minimumPoints, long startingRating, double kFactor,
                           boolean notifyPoints, List<Rank> ranks, String unrankedName) {
    }

    public enum PointsMode { FIXED, ELO, DISABLED }

    public record Rank(String id, long minPoints, String display) {
    }

    private boolean registeredHandler;

    public StatsModule(UltimateCombatPlugin plugin) {
        super(plugin, "stats", "stats.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        ConfigReader c = r.section("count");
        ConfigReader p = r.section("points");
        List<Rank> ranks = new ArrayList<>();
        for (String id : r.keys("ranks.list")) {
            ConfigReader rank = r.section("ranks.list." + id);
            ranks.add(new Rank(id, rank.longValue("min-points", 0, Long.MIN_VALUE / 4, Long.MAX_VALUE / 4), rank.string("display", id)));
        }
        ranks.sort(Comparator.comparingLong(Rank::minPoints).reversed());
        return new Settings(
                c.bool("kills", true), c.bool("deaths", true), c.bool("assists", true), c.bool("mob-kills", true),
                c.bool("combat-logs", true), c.bool("damage", true), c.bool("only-pvp-deaths", false),
                p.enumValue("mode", PointsMode.class, PointsMode.FIXED),
                p.longValue("kill", 10, -1_000_000, 1_000_000),
                p.longValue("death", -5, -1_000_000, 1_000_000),
                p.longValue("assist", 3, -1_000_000, 1_000_000),
                p.longValue("combat-log", -10, -1_000_000, 1_000_000),
                p.longValue("minimum", 0, Long.MIN_VALUE / 4, Long.MAX_VALUE / 4),
                p.longValue("elo.starting-rating", 1000, 0, 100_000),
                p.decimal("elo.k-factor", 32, 1, 1000),
                p.bool("notify", true),
                ranks,
                r.string("ranks.unranked", "&7Unranked"));
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
        return STATS;
    }

    // ------------------------------------------------------------------------------ points

    /**
     * @return the displayed points of a player (ELO adds the starting rating)
     */
    public long displayPoints(PlayerStats stats) {
        Settings s = settings();
        return s.pointsMode() == PointsMode.ELO ? s.startingRating() + stats.points() : stats.points();
    }

    /**
     * @return the value added to stored points for display (the ELO starting rating, otherwise 0)
     */
    public long pointsOffset() {
        Settings s = settings();
        return s.pointsMode() == PointsMode.ELO ? s.startingRating() : 0;
    }

    public String rankName(PlayerStats stats) {
        return rankName(displayPoints(stats));
    }

    /**
     * @return the configured rank for a displayed points value
     */
    public String rankName(long points) {
        for (Rank rank : settings().ranks()) {
            if (points >= rank.minPoints()) {
                return rank.display();
            }
        }
        return settings().unrankedName();
    }

    private long floorFor(Settings s) {
        return s.pointsMode() == PointsMode.ELO ? s.minimumPoints() - s.startingRating() : s.minimumPoints();
    }

    // ------------------------------------------------------------------------------ kill pipeline

    @Override
    public void onPlayerDeath(KillContext ctx) {
        if (ctx.statsSuppressed()) {
            return;
        }
        Settings s = settings(ctx.victim().getWorld());
        Player victim = ctx.victim();
        Player killer = ctx.killer();
        PlayerStats victimStats = plugin.players().profile(victim).stats();
        boolean pvp = ctx.isPvp();
        if (s.countDeaths() && (pvp || !s.pvpDeathsOnly())) {
            victimStats.addDeath();
        }
        if (!pvp) {
            return;
        }
        PlayerStats killerStats = plugin.players().profile(killer).stats();
        if (s.countKills()) {
            killerStats.addKill();
        }
        if (s.countAssists()) {
            for (UUID id : ctx.assists()) {
                PlayerProfile assistant = plugin.players().online(id);
                if (assistant != null) {
                    assistant.stats().addAssist();
                    if (!ctx.pointsSuppressed() && s.pointsMode() != PointsMode.DISABLED && s.assistPoints() != 0) {
                        assistant.stats().addPointsClamped(s.assistPoints(), floorFor(s));
                    }
                }
            }
        }
        if (ctx.pointsSuppressed() || s.pointsMode() == PointsMode.DISABLED) {
            return;
        }
        long gain;
        long loss;
        if (s.pointsMode() == PointsMode.ELO) {
            double rk = displayPoints(killerStats);
            double rv = displayPoints(victimStats);
            double expected = 1.0 / (1.0 + Math.pow(10, (rv - rk) / 400.0));
            gain = Math.round(s.kFactor() * (1 - expected));
            loss = -gain;
        } else {
            gain = s.killPoints();
            loss = s.deathPoints();
        }
        long applied = killerStats.addPointsClamped(gain, floorFor(s));
        long lost = victimStats.addPointsClamped(loss, floorFor(s));
        ctx.killerPointsChange(applied);
        ctx.victimPointsChange(lost);
        if (s.notifyPoints()) {
            if (applied != 0) {
                plugin.messages().send(killer, "stats.points-gained", "points", applied, "total", displayPoints(killerStats),
                        "victim", victim.getName());
            }
            if (lost != 0) {
                plugin.messages().send(victim, "stats.points-lost", "points", -lost, "total", displayPoints(victimStats),
                        "killer", killer.getName());
            }
        }
    }

    @Override
    public void onMobKill(Player killer, LivingEntity entity, EntityDeathEvent event) {
        if (settings(killer.getWorld()).countMobKills()) {
            plugin.players().profile(killer).stats().addMobKill();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatLog(CombatLogEvent event) {
        Settings s = settings(event.getPlayer().getWorld());
        PlayerStats stats = plugin.players().profile(event.getPlayer()).stats();
        if (s.countCombatLogs()) {
            stats.addCombatLog();
        }
        if (s.pointsMode() == PointsMode.FIXED && s.combatLogPoints() != 0) {
            stats.addPointsClamped(s.combatLogPoints(), floorFor(s));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!settings(victim.getWorld()).trackDamage()) {
            return;
        }
        Player attacker = plugin.kills().attribution().attacker(event);
        if (attacker == null || attacker == victim) {
            return;
        }
        double damage = event.getFinalDamage();
        plugin.players().profile(victim).stats().addDamageTaken(damage);
        plugin.players().profile(attacker).stats().addDamageDealt(damage);
    }

    // ------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        SimpleCommand stats = new SimpleCommand("stats", "stats [player]", "ultimatecombat.stats", false, this::statsCommand,
                ctx -> ctx.size() == 1 ? UCCommand.onlineNames() : List.of());
        plugin.commands().root().child(stats);
        plugin.commands().standalone("pvpstats", stats);
        SimpleCommand points = new SimpleCommand("points", "points [player]", "ultimatecombat.points", false, this::pointsCommand,
                ctx -> ctx.size() == 1 ? UCCommand.onlineNames() : List.of());
        plugin.commands().root().child(points);
        plugin.commands().standalone("pvppoints", points);
        plugin.commands().root().child(new SimpleCommand("resetstats", "resetstats <player|all> [confirm]",
                "ultimatecombat.stats.reset", false, this::resetCommand, ctx -> {
                    List<String> out = new ArrayList<>(UCCommand.onlineNames());
                    out.add("all");
                    return ctx.size() == 1 ? out : List.of("confirm");
                }));
        plugin.commands().root().child(new SimpleCommand("exportstats", "exportstats", "ultimatecombat.stats.export", false,
                this::exportCommand));
    }

    private void withTarget(CommandContext ctx, String othersPermission, java.util.function.Consumer<PlayerProfile> action) {
        if (ctx.size() == 0) {
            if (!ctx.isPlayer()) {
                ctx.reply("general.player-only");
                return;
            }
            action.accept(plugin.players().profile(ctx.player()));
            return;
        }
        if (!ctx.has(othersPermission)) {
            ctx.reply("general.no-permission");
            return;
        }
        plugin.players().lookup(ctx.arg(0)).thenAccept((Optional<PlayerProfile> opt) -> {
            if (opt.isEmpty()) {
                ctx.reply("stats.no-data", "player", ctx.arg(0));
            } else {
                action.accept(opt.get());
            }
        });
    }

    private void statsCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        withTarget(ctx, "ultimatecombat.stats.others", profile -> {
            PlayerStats st = profile.stats();
            ctx.reply("stats.view", "player", profile.name(), "kills", st.kills(), "deaths", st.deaths(),
                    "kdr", Numbers.twoDecimals(st.kdr()), "streak", st.streak(), "best_streak", st.bestStreak(),
                    "points", displayPoints(st), "rank", rankName(st), "combatlogs", st.combatLogs(), "assists", st.assists(),
                    "mob_kills", st.mobKills(), "bounties", st.bountiesClaimed(),
                    "damage_dealt", Numbers.twoDecimals(st.damageDealt()), "damage_taken", Numbers.twoDecimals(st.damageTaken()));
        });
    }

    private void pointsCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        withTarget(ctx, "ultimatecombat.points.others", profile -> ctx.reply("stats.points-view", "player", profile.name(),
                "points", displayPoints(profile.stats()), "rank", rankName(profile.stats())));
    }

    private void resetCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 1) {
            ctx.reply("general.usage", "usage", "/uc resetstats <player|all> confirm");
            return;
        }
        if (!"confirm".equalsIgnoreCase(ctx.arg(1))) {
            ctx.reply("stats.reset-confirm", "target", ctx.arg(0));
            return;
        }
        if (ctx.arg(0).equalsIgnoreCase("all")) {
            if (!plugin.storageReady()) {
                ctx.reply("general.storage-unavailable");
                return;
            }
            for (PlayerProfile p : plugin.players().onlineProfiles()) {
                p.stats().resetAll();
            }
            plugin.database().supply(c -> plugin.players().repository().resetAll(c))
                    .thenAccept(rows -> ctx.reply("stats.reset-all", "count", rows));
            return;
        }
        plugin.players().lookup(ctx.arg(0)).thenAccept(opt -> {
            if (opt.isEmpty()) {
                ctx.reply("stats.no-data", "player", ctx.arg(0));
                return;
            }
            PlayerProfile p = opt.get();
            p.stats().resetAll();
            p.statsReset(true);
            plugin.players().save(p).thenRun(() -> ctx.reply("stats.reset-player", "player", p.name()));
        });
    }

    private void exportCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (!plugin.storageReady()) {
            ctx.reply("general.storage-unavailable");
            return;
        }
        plugin.players().saveDirty();
        plugin.database().supply(c -> plugin.players().repository().exportCsv(c)).thenAccept(csv -> {
            try {
                File dir = plugin.dataFile("exports");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new java.io.IOException("cannot create " + dir);
                }
                File file = new File(dir, "stats-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".csv");
                Files.writeString(file.toPath(), csv, StandardCharsets.UTF_8);
                ctx.reply("stats.exported", "file", "exports/" + file.getName());
            } catch (java.io.IOException e) {
                ctx.reply("stats.export-failed", "error", e.getMessage());
            }
        });
    }

    /**
     * @return the stats of an online player, or {@code null}
     */
    public PlayerStats stats(UUID uuid) {
        PlayerProfile p = plugin.players().online(uuid);
        return p == null ? null : p.stats();
    }

    /**
     * Kept for API users that only need the online player's points.
     */
    public long points(Player player) {
        return displayPoints(plugin.players().profile(player).stats());
    }
}
