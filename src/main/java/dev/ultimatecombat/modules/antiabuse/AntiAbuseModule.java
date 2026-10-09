package dev.ultimatecombat.modules.antiabuse;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.player.PlayerProfile;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.util.CommandRunner;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti kill-abuse (anti-abuse.yml): kill farming, same-IP / alt accounts and spawn killing.
 * Runs first in the kill pipeline and only flags kills; other modules respect the flags.
 */
public final class AntiAbuseModule extends Module<AntiAbuseModule.Settings> implements KillHandler {

    /**
     * What a detection suppresses.
     */
    public record Suppress(boolean rewards, boolean points, boolean stats) {
        static Suppress read(ConfigReader r) {
            return new Suppress(r.bool("rewards", true), r.bool("points", true), r.bool("stats", false));
        }
    }

    public record Settings(boolean repeatedEnabled, int maxKills, long windowMillis, Suppress repeatedSuppress, boolean sameIpEnabled,
                           Suppress sameIpSuppress, boolean altEnabled, Suppress altSuppress, boolean ipChecks, String salt,
                           boolean respawnEnabled, long respawnMillis, Suppress respawnSuppress, boolean notifyKiller,
                           List<String> commands, boolean log) {
    }

    private final Map<String, Deque<Long>> kills = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> alts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> respawnedAt = new ConcurrentHashMap<>();
    private volatile String fileSalt;
    private boolean registeredHandler;

    public AntiAbuseModule(UltimateCombatPlugin plugin) {
        super(plugin, "anti-abuse", "anti-abuse.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        ConfigReader rk = r.section("repeated-kills");
        ConfigReader ip = r.section("same-ip");
        ConfigReader alt = r.section("alt-accounts");
        ConfigReader rs = r.section("respawn-kill");
        return new Settings(
                rk.bool("enabled", true),
                rk.integer("max-kills", 3, 1, 10_000),
                rk.durationMillis("window-minutes", 30 * 60_000L, ConfigReader.TimeUnitHint.MINUTES, 1000, 30L * 86_400_000L),
                Suppress.read(rk.section("suppress")),
                ip.bool("enabled", true),
                Suppress.read(ip.section("suppress")),
                alt.bool("enabled", true),
                Suppress.read(alt.section("suppress")),
                r.bool("ip-checks.enabled", true),
                r.string("ip-checks.salt", ""),
                rs.bool("enabled", true),
                rs.durationMillis("seconds", 10_000, ConfigReader.TimeUnitHint.SECONDS, 0, 3_600_000),
                Suppress.read(rs.section("suppress")),
                r.bool("notify-killer", true),
                r.stringList("commands"),
                r.bool("log.enabled", true));
    }

    @Override
    protected void onEnable() {
        if (!registeredHandler) {
            plugin.kills().register(this);
            registeredHandler = true;
        }
        plugin.players().setIpSalt(() -> isEnabled() && settings().ipChecks() ? salt() : null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadAlts(p);
        }
    }

    @Override
    protected void onDisable() {
        plugin.players().setIpSalt(() -> null);
        kills.clear();
        alts.clear();
        respawnedAt.clear();
    }

    @Override
    public boolean activeHandler() {
        return isEnabled();
    }

    @Override
    public int killPriority() {
        return ANALYZE;
    }

    /**
     * Salt for IP hashes: configured value, or a random value stored in data/ip-salt.txt.
     * Raw IP addresses are never stored.
     */
    private String salt() {
        String configured = settings().salt();
        if (!configured.isBlank()) {
            return configured;
        }
        String s = fileSalt;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (fileSalt != null) {
                return fileSalt;
            }
            File file = plugin.dataFile("data/ip-salt.txt");
            try {
                if (file.exists()) {
                    fileSalt = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                } else {
                    byte[] bytes = new byte[32];
                    new SecureRandom().nextBytes(bytes);
                    fileSalt = HexFormat.of().formatHex(bytes);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("cannot create " + parent);
                    }
                    Files.writeString(file.toPath(), fileSalt, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read/write data/ip-salt.txt: " + e.getMessage() + " (IP checks use a temporary salt)");
                fileSalt = Long.toHexString(new SecureRandom().nextLong());
            }
            return fileSalt;
        }
    }

    // ------------------------------------------------------------------------------ tracking

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.scheduler().runAsyncLater(() -> loadAlts(p), 40L);
    }

    private void loadAlts(Player player) {
        if (!settings().altEnabled() || !plugin.storageReady()) {
            return;
        }
        PlayerProfile profile = plugin.players().online(player.getUniqueId());
        if (profile == null || profile.ipHash() == null) {
            return;
        }
        String hash = profile.ipHash();
        plugin.database().supply(c -> plugin.players().repository().sharingIp(c, hash, player.getUniqueId()))
                .thenAccept(list -> alts.put(player.getUniqueId(), Set.copyOf(list)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        alts.remove(event.getPlayer().getUniqueId());
        respawnedAt.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        respawnedAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------------------ analysis

    @Override
    public void onPlayerDeath(KillContext ctx) {
        if (!ctx.isPvp()) {
            return;
        }
        Player killer = ctx.killer();
        Player victim = ctx.victim();
        if (plugin.bypasses(killer, "anti-abuse")) {
            return;
        }
        Settings s = settings(killer.getWorld());
        long now = System.currentTimeMillis();
        String reason = null;
        Suppress suppress = null;

        if (s.repeatedEnabled()) {
            String key = killer.getUniqueId() + ">" + victim.getUniqueId();
            Deque<Long> times = kills.computeIfAbsent(key, k -> new ArrayDeque<>());
            int count;
            synchronized (times) {
                while (!times.isEmpty() && now - times.peekFirst() > s.windowMillis()) {
                    times.removeFirst();
                }
                times.addLast(now);
                count = times.size();
            }
            if (count > s.maxKills()) {
                reason = "repeated-kills";
                suppress = s.repeatedSuppress();
            }
        }
        if (reason == null && s.sameIpEnabled()) {
            PlayerProfile kp = plugin.players().online(killer.getUniqueId());
            PlayerProfile vp = plugin.players().online(victim.getUniqueId());
            if (kp != null && vp != null && kp.ipHash() != null && kp.ipHash().equals(vp.ipHash())) {
                reason = "same-ip";
                suppress = s.sameIpSuppress();
            }
        }
        if (reason == null && s.altEnabled()) {
            Set<UUID> linked = alts.get(killer.getUniqueId());
            if (linked != null && linked.contains(victim.getUniqueId())) {
                reason = "alt-account";
                suppress = s.altSuppress();
            }
        }
        if (reason == null && s.respawnEnabled()) {
            Long respawn = respawnedAt.get(victim.getUniqueId());
            if (respawn != null && now - respawn < s.respawnMillis()) {
                reason = "respawn-kill";
                suppress = s.respawnSuppress();
            }
        }
        if (reason == null) {
            return;
        }
        if (suppress.rewards()) {
            ctx.suppressRewards(reason);
        }
        if (suppress.points()) {
            ctx.suppressPoints(reason);
        }
        if (suppress.stats()) {
            ctx.suppressStats(reason);
        }
        if (s.notifyKiller()) {
            plugin.messages().send(killer, "anti-abuse.suppressed." + reason, "victim", victim.getName());
        }
        CommandRunner.console(plugin, killer, s.commands(), "player", killer.getName(), "victim", victim.getName(), "reason", reason);
        plugin.debug().log("rewards", "Anti abuse: " + reason + " for " + killer.getName() + " -> " + victim.getName());
        if (s.log() && plugin.storageReady()) {
            String r = reason;
            Database db = plugin.database();
            db.run(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("abuse_log")
                        + " (data_group, killer_uuid, killer_name, victim_uuid, victim_name, reason, created_at) VALUES (?,?,?,?,?,?,?)")) {
                    ps.setString(1, db.group());
                    ps.setString(2, killer.getUniqueId().toString());
                    ps.setString(3, killer.getName());
                    ps.setString(4, victim.getUniqueId().toString());
                    ps.setString(5, victim.getName());
                    ps.setString(6, r);
                    ps.setLong(7, now);
                    ps.executeUpdate();
                }
            });
        }
    }

    /**
     * Periodic cleanup of expired counters (automatic timeout and reset).
     */
    private void purge() {
        long window = settings().windowMillis();
        long now = System.currentTimeMillis();
        kills.values().forEach(d -> {
            synchronized (d) {
                while (!d.isEmpty() && now - d.peekFirst() > window) {
                    d.removeFirst();
                }
            }
        });
        kills.values().removeIf(d -> {
            synchronized (d) {
                return d.isEmpty();
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnyRespawnPurge(PlayerRespawnEvent event) {
        if (kills.size() > 1000) {
            purge();
        }
    }

    // ------------------------------------------------------------------------------ review

    @Override
    public void registerCommands() {
        plugin.commands().root().child(new SimpleCommand("abuse", "abuse <player>", "ultimatecombat.antiabuse.review", false,
                this::review, ctx -> ctx.size() == 1 ? UCCommand.onlineNames() : List.of()));
    }

    @SuppressWarnings("deprecation")
    private void review(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 1) {
            ctx.reply("general.usage", "usage", "/uc abuse <player>");
            return;
        }
        if (!plugin.storageReady()) {
            ctx.reply("general.storage-unavailable");
            return;
        }
        Player online = Bukkit.getPlayerExact(ctx.arg(0));
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(ctx.arg(0));
        Database db = plugin.database();
        db.supply(c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT victim_name, reason, created_at FROM " + db.t("abuse_log")
                    + " WHERE data_group = ? AND killer_uuid = ? ORDER BY created_at DESC LIMIT 15")) {
                ps.setString(1, db.group());
                ps.setString(2, target.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                    while (rs.next()) {
                        rows.add(new String[]{rs.getString(1), rs.getString(2), f.format(new Date(rs.getLong(3)))});
                    }
                }
            }
            return rows;
        }).thenAccept(rows -> {
            ctx.reply("anti-abuse.review-header", "player", ctx.arg(0), "count", rows.size(),
                    "alts", alts.getOrDefault(target.getUniqueId(), Set.of()).size());
            for (String[] r : rows) {
                ctx.reply("anti-abuse.review-entry", "victim", r[0], "reason", r[1], "date", r[2]);
            }
        });
    }
}
