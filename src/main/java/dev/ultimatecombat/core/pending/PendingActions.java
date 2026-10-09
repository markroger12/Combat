package dev.ultimatecombat.core.pending;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Persistent, exactly-once actions applied when a player joins (rejoin punishments, inventory
 * clearing after a ghost player died, refunds ...). Survives restarts and works across servers
 * sharing one database.
 *
 * <p>An action is claimed with {@code UPDATE ... SET state='DONE' WHERE id=? AND state='PENDING'};
 * only the caller that changed the row applies the action, so a crash or a second server can
 * never apply it twice.</p>
 */
public final class PendingActions implements Listener {

    public record Action(long id, UUID player, String type, String payload, long createdAt) {
    }

    private final UltimateCombatPlugin plugin;
    private final Map<String, BiConsumer<Player, Action>> handlers = new ConcurrentHashMap<>();
    private volatile long expireMillis = 30L * 86_400_000L;

    public PendingActions(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public void setExpireMillis(long expireMillis) {
        this.expireMillis = expireMillis;
    }

    public void handler(String type, BiConsumer<Player, Action> handler) {
        handlers.put(type, handler);
    }

    /**
     * Removes a handler; actions of that type stay pending until a handler exists again.
     */
    public void removeHandler(String type) {
        handlers.remove(type);
    }

    private Database db() {
        return plugin.database();
    }

    public CompletableFuture<Long> add(UUID player, String type, String payload) {
        if (!plugin.storageReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("storage unavailable"));
        }
        Database db = db();
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("pending_actions")
                    + " (data_group, uuid, type, payload, created_at, state) VALUES (?, ?, ?, ?, ?, 'PENDING')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, db.group());
                ps.setString(2, player.toString());
                ps.setString(3, type);
                ps.setString(4, payload == null ? "" : payload);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
        });
    }

    public CompletableFuture<List<Action>> pending(UUID player) {
        if (!plugin.storageReady()) {
            return CompletableFuture.completedFuture(List.of());
        }
        Database db = db();
        long cutoff = System.currentTimeMillis() - expireMillis;
        return db.supply(c -> {
            List<Action> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, type, payload, created_at FROM " + db.t("pending_actions")
                    + " WHERE data_group = ? AND uuid = ? AND state = 'PENDING' AND created_at >= ? ORDER BY id")) {
                ps.setString(1, db.group());
                ps.setString(2, player.toString());
                ps.setLong(3, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Action(rs.getLong("id"), player, rs.getString("type"), rs.getString("payload"), rs.getLong("created_at")));
                    }
                }
            }
            return out;
        });
    }

    /**
     * Claims an action. Completes with {@code true} only for the single caller that claimed it.
     */
    public CompletableFuture<Boolean> claim(long id) {
        if (!plugin.storageReady()) {
            return CompletableFuture.completedFuture(false);
        }
        Database db = db();
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("pending_actions")
                    + " SET state = 'DONE' WHERE id = ? AND state = 'PENDING'")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Cancels a pending action without applying it.
     */
    public CompletableFuture<Boolean> cancel(long id) {
        if (!plugin.storageReady()) {
            return CompletableFuture.completedFuture(false);
        }
        Database db = db();
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("pending_actions")
                    + " SET state = 'CANCELLED' WHERE id = ? AND state = 'PENDING'")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Give other join handlers (and data loading) a moment before applying punishments.
        plugin.scheduler().runEntityLater(player, () -> process(player), 10L);
    }

    public void process(Player player) {
        pending(player.getUniqueId()).thenAccept(actions -> {
            for (Action action : actions) {
                BiConsumer<Player, Action> handler = handlers.get(action.type());
                if (handler == null) {
                    continue; // feature currently disabled; keep pending
                }
                claim(action.id()).thenAccept(claimed -> {
                    if (!claimed) {
                        return;
                    }
                    plugin.scheduler().runEntity(player, () -> {
                        try {
                            handler.accept(player, action);
                        } catch (RuntimeException e) {
                            plugin.getLogger().log(Level.SEVERE, "Pending action " + action.type() + " for " + player.getName() + " failed", e);
                        }
                    });
                });
            }
        });
    }

    /**
     * Deletes finished and expired entries.
     */
    public void purge() {
        if (!plugin.storageReady()) {
            return;
        }
        Database db = db();
        long cutoff = System.currentTimeMillis() - expireMillis;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.t("pending_actions")
                    + " WHERE data_group = ? AND (state <> 'PENDING' OR created_at < ?)")) {
                ps.setString(1, db.group());
                ps.setLong(2, cutoff);
                ps.executeUpdate();
            }
        });
    }
}
