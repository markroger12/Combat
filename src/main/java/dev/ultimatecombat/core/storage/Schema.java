package dev.ultimatecombat.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Versioned schema. Each migration runs exactly once per database; the applied version is stored
 * in {@code <prefix>schema_version}. New plugin versions append migrations, never edit old ones.
 */
public final class Schema {

    public static final int LATEST = 1;

    private final Database db;
    private final Logger logger;

    public Schema(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * Brings the schema up to date. Runs synchronously during plugin start-up.
     */
    public void migrate() throws SQLException {
        db.supplySync(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + db.t("schema_version") + " (version INT NOT NULL)");
            }
            int current = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM " + db.t("schema_version"))) {
                if (rs.next()) {
                    current = rs.getInt(1);
                }
            }
            if (current > LATEST) {
                logger.warning("Database schema version " + current + " is newer than this plugin supports (" + LATEST
                        + "). Running with the newer schema; downgrade is not supported.");
                return null;
            }
            for (int version = current + 1; version <= LATEST; version++) {
                logger.info("Applying database migration v" + version);
                for (String sql : statements(version)) {
                    execute(c, sql);
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("schema_version") + " (version) VALUES (?)")) {
                    ps.setInt(1, version);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            if (sql.startsWith("CREATE INDEX") && (msg.contains("duplicate") || msg.contains("already exists"))) {
                return;
            }
            throw e;
        }
    }

    private List<String> statements(int version) {
        List<String> s = new ArrayList<>();
        if (version == 1) {
            String text = db.longText();
            String pk = db.autoIncrementPk();
            boolean sqlite = db.type() == Database.Type.SQLITE;
            String ifNotExists = sqlite ? "IF NOT EXISTS " : "";

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("players") + " ("
                    + "uuid VARCHAR(36) NOT NULL, data_group VARCHAR(64) NOT NULL, name VARCHAR(32) NOT NULL, "
                    + "pvp_enabled SMALLINT NULL, pvp_forced SMALLINT NULL, pvp_cooldown_until BIGINT NOT NULL DEFAULT 0, "
                    + "grace_remaining BIGINT NOT NULL DEFAULT 0, grace_active SMALLINT NOT NULL DEFAULT 0, "
                    + "grace_initialized SMALLINT NOT NULL DEFAULT 0, ip_hash VARCHAR(64) NULL, "
                    + "first_join BIGINT NOT NULL DEFAULT 0, last_seen BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (uuid, data_group))");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_players_name") + " ON " + db.t("players") + " (data_group, name)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_players_ip") + " ON " + db.t("players") + " (data_group, ip_hash)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("stats") + " ("
                    + "uuid VARCHAR(36) NOT NULL, data_group VARCHAR(64) NOT NULL, name VARCHAR(32) NOT NULL DEFAULT '', "
                    + "kills INT NOT NULL DEFAULT 0, deaths INT NOT NULL DEFAULT 0, combat_logs INT NOT NULL DEFAULT 0, "
                    + "points BIGINT NOT NULL DEFAULT 0, streak INT NOT NULL DEFAULT 0, best_streak INT NOT NULL DEFAULT 0, "
                    + "assists INT NOT NULL DEFAULT 0, mob_kills INT NOT NULL DEFAULT 0, bounties_claimed INT NOT NULL DEFAULT 0, "
                    + "bounty_earnings DOUBLE NOT NULL DEFAULT 0, damage_dealt DOUBLE NOT NULL DEFAULT 0, "
                    + "damage_taken DOUBLE NOT NULL DEFAULT 0, PRIMARY KEY (uuid, data_group))");
            for (String col : List.of("kills", "deaths", "points", "best_streak", "combat_logs", "streak")) {
                s.add("CREATE INDEX " + ifNotExists + db.t("idx_stats_" + col) + " ON " + db.t("stats") + " (data_group, " + col + ")");
            }

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("bounties") + " ("
                    + "id " + pk + ", data_group VARCHAR(64) NOT NULL, target_uuid VARCHAR(36) NOT NULL, "
                    + "target_name VARCHAR(32) NOT NULL, setter_uuid VARCHAR(36) NULL, setter_name VARCHAR(32) NOT NULL, "
                    + "amount DOUBLE NOT NULL, tax DOUBLE NOT NULL DEFAULT 0, anonymous SMALLINT NOT NULL DEFAULT 0, "
                    + "created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL DEFAULT 0, state VARCHAR(16) NOT NULL, "
                    + "resolved_by VARCHAR(36) NULL, resolved_at BIGINT NULL)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_bounties_target") + " ON " + db.t("bounties") + " (data_group, state, target_uuid)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("pending_actions") + " ("
                    + "id " + pk + ", data_group VARCHAR(64) NOT NULL, uuid VARCHAR(36) NOT NULL, type VARCHAR(32) NOT NULL, "
                    + "payload " + text + " NOT NULL, created_at BIGINT NOT NULL, state VARCHAR(16) NOT NULL)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_pending_uuid") + " ON " + db.t("pending_actions") + " (data_group, uuid, state)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("corpses") + " ("
                    + "id VARCHAR(36) NOT NULL PRIMARY KEY, data_group VARCHAR(64) NOT NULL, owner_uuid VARCHAR(36) NOT NULL, "
                    + "owner_name VARCHAR(32) NOT NULL, world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, "
                    + "z DOUBLE NOT NULL, yaw FLOAT NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, "
                    + "items " + text + " NOT NULL, xp INT NOT NULL DEFAULT 0, killer_uuid VARCHAR(36) NULL, "
                    + "state VARCHAR(16) NOT NULL)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_corpses_group") + " ON " + db.t("corpses") + " (data_group, state)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("stattrak") + " ("
                    + "item_id VARCHAR(36) NOT NULL PRIMARY KEY, data_group VARCHAR(64) NOT NULL, owner_uuid VARCHAR(36) NOT NULL, "
                    + "owner_name VARCHAR(32) NOT NULL, kills INT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, "
                    + "deleted SMALLINT NOT NULL DEFAULT 0)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("kill_recaps") + " ("
                    + "id " + pk + ", recap_key VARCHAR(36) NOT NULL, data_group VARCHAR(64) NOT NULL, killer_uuid VARCHAR(36) NULL, victim_uuid VARCHAR(36) NOT NULL, "
                    + "created_at BIGINT NOT NULL, payload " + text + " NOT NULL)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_recaps_victim") + " ON " + db.t("kill_recaps") + " (data_group, victim_uuid)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_recaps_killer") + " ON " + db.t("kill_recaps") + " (data_group, killer_uuid)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_recaps_key") + " ON " + db.t("kill_recaps") + " (recap_key)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("combat_log_history") + " ("
                    + "id " + pk + ", data_group VARCHAR(64) NOT NULL, uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                    + "created_at BIGINT NOT NULL, world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                    + "combat_type VARCHAR(8) NOT NULL, opponent VARCHAR(64) NULL, punishment VARCHAR(32) NOT NULL)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_cl_uuid") + " ON " + db.t("combat_log_history") + " (data_group, uuid)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("abuse_log") + " ("
                    + "id " + pk + ", data_group VARCHAR(64) NOT NULL, killer_uuid VARCHAR(36) NOT NULL, "
                    + "killer_name VARCHAR(32) NOT NULL, victim_uuid VARCHAR(36) NOT NULL, victim_name VARCHAR(32) NOT NULL, "
                    + "reason VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL)");
            s.add("CREATE INDEX " + ifNotExists + db.t("idx_abuse_killer") + " ON " + db.t("abuse_log") + " (data_group, killer_uuid)");

            s.add("CREATE TABLE IF NOT EXISTS " + db.t("heads") + " ("
                    + "id VARCHAR(36) NOT NULL PRIMARY KEY, data_group VARCHAR(64) NOT NULL, victim_uuid VARCHAR(36) NOT NULL, "
                    + "victim_name VARCHAR(32) NOT NULL, killer_uuid VARCHAR(36) NULL, value DOUBLE NOT NULL DEFAULT 0, "
                    + "created_at BIGINT NOT NULL, sold_by VARCHAR(36) NULL, sold_at BIGINT NULL)");
        }
        return s;
    }
}
