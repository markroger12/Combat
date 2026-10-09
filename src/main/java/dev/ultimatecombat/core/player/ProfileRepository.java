package dev.ultimatecombat.core.player;

import dev.ultimatecombat.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQL access for {@link PlayerProfile} and {@link PlayerStats}.
 */
public final class ProfileRepository {

    private final Database db;

    public ProfileRepository(Database db) {
        this.db = db;
    }

    public PlayerProfile load(Connection c, UUID uuid, String name) throws SQLException {
        PlayerProfile profile = new PlayerProfile(uuid, name);
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + db.t("players") + " WHERE uuid = ? AND data_group = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, db.group());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    readProfile(profile, rs);
                    profile.existsInDatabase(true);
                    if (name == null) {
                        profile.name(rs.getString("name"));
                    }
                }
            }
        }
        loadStats(c, profile);
        profile.markClean();
        return profile;
    }

    public PlayerProfile loadByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM " + db.t("players")
                + " WHERE data_group = ? AND LOWER(name) = LOWER(?) ORDER BY last_seen DESC")) {
            ps.setString(1, db.group());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return load(c, UUID.fromString(rs.getString("uuid")), rs.getString("name"));
                }
            }
        }
        return null;
    }

    private static void readProfile(PlayerProfile p, ResultSet rs) throws SQLException {
        int pvp = rs.getInt("pvp_enabled");
        p.pvpEnabled(rs.wasNull() ? null : pvp != 0);
        int forced = rs.getInt("pvp_forced");
        p.pvpForced(rs.wasNull() ? null : forced != 0);
        p.pvpCooldownUntil(rs.getLong("pvp_cooldown_until"));
        p.graceRemaining(rs.getLong("grace_remaining"));
        p.graceActive(rs.getInt("grace_active") != 0);
        p.graceInitialized(rs.getInt("grace_initialized") != 0);
        p.ipHash(rs.getString("ip_hash"));
        p.firstJoin(rs.getLong("first_join"));
        p.lastSeen(rs.getLong("last_seen"));
    }

    private void loadStats(Connection c, PlayerProfile profile) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + db.t("stats") + " WHERE uuid = ? AND data_group = ?")) {
            ps.setString(1, profile.uuid().toString());
            ps.setString(2, db.group());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    profile.stats().load(rs.getInt("kills"), rs.getInt("deaths"), rs.getInt("combat_logs"), rs.getLong("points"),
                            rs.getInt("streak"), rs.getInt("best_streak"), rs.getInt("assists"), rs.getInt("mob_kills"),
                            rs.getInt("bounties_claimed"), rs.getDouble("bounty_earnings"), rs.getDouble("damage_dealt"),
                            rs.getDouble("damage_taken"));
                }
            }
        }
    }

    /**
     * Saves profile state (absolute values) and statistics (deltas).
     */
    public void save(Connection c, PlayerProfile p, PlayerStats.Delta delta) throws SQLException {
        if (!p.loadFailed() && p.isDirty()) {
            String sql = db.upsert(db.t("players"),
                    new String[]{"uuid", "data_group", "name", "pvp_enabled", "pvp_forced", "pvp_cooldown_until", "grace_remaining",
                            "grace_active", "grace_initialized", "ip_hash", "first_join", "last_seen"},
                    new String[]{"uuid", "data_group"},
                    new String[]{"name = NEW(name)", "pvp_enabled = NEW(pvp_enabled)", "pvp_forced = NEW(pvp_forced)",
                            "pvp_cooldown_until = NEW(pvp_cooldown_until)", "grace_remaining = NEW(grace_remaining)",
                            "grace_active = NEW(grace_active)", "grace_initialized = NEW(grace_initialized)",
                            "ip_hash = NEW(ip_hash)", "last_seen = NEW(last_seen)"});
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, p.uuid().toString());
                ps.setString(2, db.group());
                ps.setString(3, p.name() == null ? "" : p.name());
                setNullableBool(ps, 4, p.pvpEnabled());
                setNullableBool(ps, 5, p.pvpForced());
                ps.setLong(6, p.pvpCooldownUntil());
                ps.setLong(7, p.graceRemaining());
                ps.setInt(8, p.graceActive() ? 1 : 0);
                ps.setInt(9, p.graceInitialized() ? 1 : 0);
                ps.setString(10, p.ipHash());
                ps.setLong(11, p.firstJoin());
                ps.setLong(12, p.lastSeen());
                ps.executeUpdate();
            }
            p.existsInDatabase(true);
        }
        if (p.statsReset()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("stats") + " SET kills = 0, deaths = 0, combat_logs = 0, "
                    + "points = 0, streak = 0, best_streak = 0, assists = 0, mob_kills = 0, bounties_claimed = 0, bounty_earnings = 0, "
                    + "damage_dealt = 0, damage_taken = 0 WHERE uuid = ? AND data_group = ?")) {
                ps.setString(1, p.uuid().toString());
                ps.setString(2, db.group());
                ps.executeUpdate();
            }
            p.statsReset(false);
        }
        if (delta != null && !delta.isEmpty()) {
            saveDelta(c, p.uuid(), p.name(), delta);
        }
    }

    public void saveDelta(Connection c, UUID uuid, String name, PlayerStats.Delta d) throws SQLException {
        String g = db.greatest();
        String streakUpdate = d.streak == null ? "streak = streak" : "streak = NEW(streak)";
        String sql = db.upsert(db.t("stats"),
                new String[]{"uuid", "data_group", "name", "kills", "deaths", "combat_logs", "points", "streak", "best_streak",
                        "assists", "mob_kills", "bounties_claimed", "bounty_earnings", "damage_dealt", "damage_taken"},
                new String[]{"uuid", "data_group"},
                new String[]{"name = NEW(name)", "kills = kills + NEW(kills)", "deaths = deaths + NEW(deaths)",
                        "combat_logs = combat_logs + NEW(combat_logs)", "points = points + NEW(points)", streakUpdate,
                        "best_streak = " + g + "(best_streak, NEW(best_streak))", "assists = assists + NEW(assists)",
                        "mob_kills = mob_kills + NEW(mob_kills)", "bounties_claimed = bounties_claimed + NEW(bounties_claimed)",
                        "bounty_earnings = bounty_earnings + NEW(bounty_earnings)", "damage_dealt = damage_dealt + NEW(damage_dealt)",
                        "damage_taken = damage_taken + NEW(damage_taken)"});
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, db.group());
            ps.setString(3, name == null ? "" : name);
            ps.setInt(4, d.kills);
            ps.setInt(5, d.deaths);
            ps.setInt(6, d.combatLogs);
            ps.setLong(7, d.points);
            ps.setInt(8, d.streak == null ? 0 : d.streak);
            ps.setInt(9, d.bestStreak);
            ps.setInt(10, d.assists);
            ps.setInt(11, d.mobKills);
            ps.setInt(12, d.bountiesClaimed);
            ps.setDouble(13, d.bountyEarnings);
            ps.setDouble(14, d.damageDealt);
            ps.setDouble(15, d.damageTaken);
            ps.executeUpdate();
        }
    }

    private static void setNullableBool(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.SMALLINT);
        } else {
            ps.setInt(index, value ? 1 : 0);
        }
    }

    /**
     * One leaderboard row.
     */
    public record TopEntry(UUID uuid, String name, double value) {
    }

    public List<TopEntry> top(Connection c, PlayerStats.Stat stat, int limit, int offset) throws SQLException {
        String expr = column(stat);
        List<TopEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, " + expr + " AS v FROM " + db.t("stats")
                + " WHERE data_group = ? ORDER BY v DESC, name ASC LIMIT ? OFFSET ?")) {
            ps.setString(1, db.group());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TopEntry(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getDouble("v")));
                }
            }
        }
        return out;
    }

    /**
     * @return 1-based rank of a player for a statistic, or 0 when unranked
     */
    public int rank(Connection c, PlayerStats.Stat stat, UUID uuid) throws SQLException {
        String expr = column(stat);
        double value;
        try (PreparedStatement ps = c.prepareStatement("SELECT " + expr + " AS v FROM " + db.t("stats") + " WHERE uuid = ? AND data_group = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, db.group());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                value = rs.getDouble("v");
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + db.t("stats") + " WHERE data_group = ? AND " + expr + " > ?")) {
            ps.setString(1, db.group());
            ps.setDouble(2, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 0;
            }
        }
    }

    public static String column(PlayerStats.Stat stat) {
        return switch (stat) {
            case KILLS -> "kills";
            case DEATHS -> "deaths";
            case COMBAT_LOGS -> "combat_logs";
            case POINTS -> "points";
            case STREAK -> "streak";
            case BEST_STREAK -> "best_streak";
            case ASSISTS -> "assists";
            case MOB_KILLS -> "mob_kills";
            case BOUNTIES_CLAIMED -> "bounties_claimed";
            case BOUNTY_EARNINGS -> "bounty_earnings";
            case DAMAGE_DEALT -> "damage_dealt";
            case DAMAGE_TAKEN -> "damage_taken";
            case KDR -> "(CASE WHEN deaths = 0 THEN kills * 1.0 ELSE kills * 1.0 / deaths END)";
        };
    }

    public int resetAll(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.t("stats") + " WHERE data_group = ?")) {
            ps.setString(1, db.group());
            return ps.executeUpdate();
        }
    }

    /**
     * @return uuids of players (other than {@code exclude}) that share an IP hash
     */
    public List<UUID> sharingIp(Connection c, String ipHash, UUID exclude) throws SQLException {
        List<UUID> out = new ArrayList<>();
        if (ipHash == null) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + db.t("players") + " WHERE data_group = ? AND ip_hash = ? AND uuid <> ?")) {
            ps.setString(1, db.group());
            ps.setString(2, ipHash);
            ps.setString(3, exclude.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return out;
    }

    /**
     * Exports every statistic row of the data group as CSV text.
     */
    public String exportCsv(Connection c) throws SQLException {
        StringBuilder sb = new StringBuilder("uuid,name,kills,deaths,combat_logs,points,streak,best_streak,assists,mob_kills,"
                + "bounties_claimed,bounty_earnings,damage_dealt,damage_taken\n");
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + db.t("stats") + " WHERE data_group = ? ORDER BY name")) {
            ps.setString(1, db.group());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append(rs.getString("uuid")).append(',').append(csv(rs.getString("name"))).append(',')
                            .append(rs.getInt("kills")).append(',').append(rs.getInt("deaths")).append(',')
                            .append(rs.getInt("combat_logs")).append(',').append(rs.getLong("points")).append(',')
                            .append(rs.getInt("streak")).append(',').append(rs.getInt("best_streak")).append(',')
                            .append(rs.getInt("assists")).append(',').append(rs.getInt("mob_kills")).append(',')
                            .append(rs.getInt("bounties_claimed")).append(',').append(rs.getDouble("bounty_earnings")).append(',')
                            .append(rs.getDouble("damage_dealt")).append(',').append(rs.getDouble("damage_taken")).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
