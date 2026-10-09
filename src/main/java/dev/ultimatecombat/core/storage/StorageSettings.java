package dev.ultimatecombat.core.storage;

import dev.ultimatecombat.core.config.ConfigReader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of {@code storage.yml}. Changing storage settings requires a restart.
 */
public record StorageSettings(Database.Type type, String dataGroup, String tablePrefix, String sqliteFile,
                              String host, int port, String database, String username, String password, boolean useSsl,
                              Map<String, String> properties, int poolSize, int minimumIdle, long connectionTimeoutMillis,
                              long maxLifetimeMillis, long keepaliveMillis, int threads, long autosaveMillis,
                              long shutdownTimeoutMillis, long loginLoadTimeoutMillis, boolean denyLoginOnFailure,
                              RedisSettings redis) {

    public record RedisSettings(boolean enabled, String host, int port, String password, boolean ssl, String channel,
                                String serverId) {
    }

    public static StorageSettings read(ConfigReader r) {
        Database.Type type = r.enumValue("type", Database.Type.class, Database.Type.SQLITE);
        String group = r.string("data-group", "default");
        if (!group.matches("[A-Za-z0-9_.-]{1,64}")) {
            r.warn("data-group", group, "Invalid data group name", "1-64 letters, digits, _ . -", "default");
            group = "default";
        }
        String prefix = r.string("table-prefix", "uc_");
        if (!prefix.matches("[A-Za-z0-9_]{0,32}")) {
            r.warn("table-prefix", prefix, "Invalid table prefix", "letters, digits and _ (max 32)", "uc_");
            prefix = "uc_";
        }
        ConfigReader m = r.section("mysql");
        Map<String, String> props = new LinkedHashMap<>();
        for (String key : m.keys("properties")) {
            props.put(key, m.string("properties." + key, ""));
        }
        ConfigReader pool = r.section("pool");
        ConfigReader redis = r.section("redis");
        return new StorageSettings(type, group, prefix,
                r.string("sqlite.file", "data/database.db"),
                m.string("host", "localhost"), m.integer("port", 3306, 1, 65535), m.string("database", "minecraft"),
                m.string("username", "root"), m.string("password", ""), m.bool("use-ssl", false), props,
                pool.integer("maximum-pool-size", 8, 1, 100), pool.integer("minimum-idle", 2, 0, 100),
                pool.durationMillis("connection-timeout", 10_000, ConfigReader.TimeUnitHint.MILLIS, 250, 600_000),
                pool.durationMillis("max-lifetime", 1_800_000, ConfigReader.TimeUnitHint.MILLIS, 30_000, 86_400_000),
                pool.durationMillis("keepalive-time", 0, ConfigReader.TimeUnitHint.MILLIS, 0, 86_400_000),
                r.integer("async-threads", 2, 1, 16),
                r.durationMillis("autosave-interval", 300_000, ConfigReader.TimeUnitHint.SECONDS, 30_000, 86_400_000),
                r.durationMillis("shutdown-timeout", 15_000, ConfigReader.TimeUnitHint.MILLIS, 1000, 300_000),
                r.durationMillis("login-load-timeout", 5_000, ConfigReader.TimeUnitHint.MILLIS, 500, 60_000),
                r.bool("deny-login-on-load-failure", false),
                new RedisSettings(redis.bool("enabled", false), redis.string("host", "localhost"),
                        redis.integer("port", 6379, 1, 65535), redis.string("password", ""), redis.bool("ssl", false),
                        redis.string("channel", "ultimatecombat"), redis.string("server-id", "")));
    }
}
