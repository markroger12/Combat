package dev.ultimatecombat.core.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cooldown tracker keyed by player and an arbitrary feature key. Expired entries are
 * removed lazily and by {@link #purgeExpired()}.
 */
public final class Cooldowns {

    private final Map<UUID, Map<String, Long>> data = new ConcurrentHashMap<>();

    public void set(UUID player, String key, long durationMillis) {
        if (durationMillis <= 0) {
            clear(player, key);
            return;
        }
        data.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(key, System.currentTimeMillis() + durationMillis);
    }

    public long remaining(UUID player, String key) {
        Map<String, Long> map = data.get(player);
        if (map == null) {
            return 0;
        }
        Long until = map.get(key);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            map.remove(key);
            return 0;
        }
        return left;
    }

    public boolean active(UUID player, String key) {
        return remaining(player, key) > 0;
    }

    public void clear(UUID player, String key) {
        Map<String, Long> map = data.get(player);
        if (map != null) {
            map.remove(key);
        }
    }

    public void clear(UUID player) {
        data.remove(player);
    }

    public void clearAll() {
        data.clear();
    }

    public Map<String, Long> all(UUID player) {
        Map<String, Long> map = data.get(player);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        data.values().forEach(m -> m.values().removeIf(until -> until <= now));
        data.values().removeIf(Map::isEmpty);
    }
}
