package dev.ultimatecombat.core.sync;

import dev.ultimatecombat.core.storage.StorageSettings;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Jedis based pub/sub transport. Only loaded when Redis is enabled and Jedis is present.
 * Message format: {@code serverId \u0001 type \u0001 arg...}; own messages are ignored.
 */
final class RedisBridge {

    private static final String SEP = "\u0001";

    private final Logger logger;
    private final StorageSettings.RedisSettings settings;
    private final String serverId;
    private final BiConsumer<String, String[]> receiver;
    private final JedisPool pool;
    private volatile JedisPubSub subscriber;
    private volatile boolean running;
    private Thread thread;

    RedisBridge(Logger logger, StorageSettings.RedisSettings settings, String serverId, BiConsumer<String, String[]> receiver) {
        this.logger = logger;
        this.settings = settings;
        this.serverId = serverId;
        this.receiver = receiver;
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(8);
        String password = settings.password().isEmpty() ? null : settings.password();
        this.pool = new JedisPool(config, settings.host(), settings.port(), 5000, password, settings.ssl());
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        }
    }

    void start() {
        running = true;
        thread = new Thread(this::loop, "UltimateCombat-Redis");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try (Jedis jedis = pool.getResource()) {
                subscriber = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        String[] parts = message.split(SEP, -1);
                        if (parts.length < 2 || parts[0].equals(serverId)) {
                            return;
                        }
                        receiver.accept(parts[1], Arrays.copyOfRange(parts, 2, parts.length));
                    }
                };
                jedis.subscribe(subscriber, settings.channel());
            } catch (RuntimeException e) {
                if (running) {
                    logger.warning("Redis subscription lost (" + e.getMessage() + "), retrying in 5s");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    void publish(String type, String... args) {
        StringBuilder sb = new StringBuilder(serverId).append(SEP).append(type);
        for (String a : args) {
            sb.append(SEP).append(a == null ? "" : a.replace(SEP, ""));
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(settings.channel(), sb.toString());
        } catch (RuntimeException e) {
            logger.fine("Redis publish failed: " + e.getMessage());
        }
    }

    void stop() {
        running = false;
        JedisPubSub s = subscriber;
        if (s != null) {
            try {
                s.unsubscribe();
            } catch (RuntimeException ignored) {
                // connection already closed
            }
        }
        pool.close();
        if (thread != null) {
            thread.interrupt();
        }
    }
}
