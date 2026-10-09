package dev.ultimatecombat.core.sync;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.scheduler.Platform;
import dev.ultimatecombat.core.storage.StorageSettings;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Optional cross-server synchronisation over Redis pub/sub. Servers sharing a MySQL/MariaDB
 * database already share statistics; Redis additionally pushes cache invalidations (statistics,
 * bounties, PvP toggles) so other servers see changes immediately instead of on next load.
 */
public final class SyncService {

    private final UltimateCombatPlugin plugin;
    private final Map<String, Consumer<String[]>> handlers = new ConcurrentHashMap<>();
    private volatile RedisBridge bridge;
    private volatile String serverId = UUID.randomUUID().toString().substring(0, 8);

    public SyncService(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(StorageSettings.RedisSettings settings) {
        if (settings == null || !settings.enabled()) {
            return;
        }
        if (!Platform.classExists("redis.clients.jedis.JedisPool")) {
            plugin.getLogger().warning("Redis is enabled in storage.yml but the Jedis library is unavailable; synchronisation disabled.");
            return;
        }
        if (!settings.serverId().isEmpty()) {
            serverId = settings.serverId();
        }
        try {
            RedisBridge b = new RedisBridge(plugin.getLogger(), settings, serverId, this::receive);
            b.start();
            bridge = b;
            plugin.getLogger().info("Redis synchronisation active on channel '" + settings.channel() + "' as server '" + serverId + "'");
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Could not start Redis synchronisation: " + e.getMessage());
        }
    }

    public boolean active() {
        return bridge != null;
    }

    public void stop() {
        RedisBridge b = bridge;
        bridge = null;
        if (b != null) {
            b.stop();
        }
    }

    public void on(String type, Consumer<String[]> handler) {
        handlers.put(type, handler);
    }

    public void publish(String type, String... args) {
        RedisBridge b = bridge;
        if (b == null) {
            return;
        }
        plugin.scheduler().runAsync(() -> b.publish(type, args));
    }

    /**
     * Dispatches a message received from another server (package-private for tests).
     */
    void receive(String type, String[] args) {
        Consumer<String[]> handler = handlers.get(type);
        if (handler == null) {
            return;
        }
        plugin.scheduler().runGlobal(() -> {
            try {
                handler.accept(args);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Error handling sync message " + type, e);
            }
        });
    }
}
