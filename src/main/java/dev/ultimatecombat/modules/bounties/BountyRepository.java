package dev.ultimatecombat.modules.bounties;

import dev.ultimatecombat.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQL access for bounties. State changes use {@code WHERE state = 'ACTIVE'} so every bounty is
 * claimed, expired or removed exactly once, even across several servers.
 */
final class BountyRepository {

    private final Database db;

    BountyRepository(Database db) {
        this.db = db;
    }

    List<Bounty> loadActive(Connection c, UUID target) throws SQLException {
        List<Bounty> out = new ArrayList<>();
        String sql = "SELECT * FROM " + db.t("bounties") + " WHERE data_group = ? AND state = 'ACTIVE'"
                + (target == null ? "" : " AND target_uuid = ?");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, db.group());
            if (target != null) {
                ps.setString(2, target.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String setter = rs.getString("setter_uuid");
                    out.add(new Bounty(rs.getLong("id"), UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"),
                            setter == null ? null : UUID.fromString(setter), rs.getString("setter_name"), rs.getDouble("amount"),
                            rs.getDouble("tax"), rs.getInt("anonymous") != 0, rs.getLong("created_at"), rs.getLong("expires_at")));
                }
            }
        }
        return out;
    }

    long insert(Connection c, Bounty b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("bounties")
                + " (data_group, target_uuid, target_name, setter_uuid, setter_name, amount, tax, anonymous, created_at, expires_at, state)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?, 'ACTIVE')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, db.group());
            ps.setString(2, b.target().toString());
            ps.setString(3, b.targetName());
            if (b.setter() == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, b.setter().toString());
            }
            ps.setString(5, b.setterName());
            ps.setDouble(6, b.amount());
            ps.setDouble(7, b.tax());
            ps.setInt(8, b.anonymous() ? 1 : 0);
            ps.setLong(9, b.createdAt());
            ps.setLong(10, b.expiresAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    /**
     * Moves a bounty out of ACTIVE.
     *
     * @return {@code true} if this call performed the change
     */
    boolean finish(Connection c, long id, String state, UUID resolvedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("bounties")
                + " SET state = ?, resolved_by = ?, resolved_at = ? WHERE id = ? AND state = 'ACTIVE'")) {
            ps.setString(1, state);
            ps.setString(2, resolvedBy == null ? null : resolvedBy.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, id);
            return ps.executeUpdate() == 1;
        }
    }
}
