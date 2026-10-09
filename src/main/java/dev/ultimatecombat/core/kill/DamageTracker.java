package dev.ultimatecombat.core.kill;

import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers recent damage per victim (bounded). Used for kill credit, assists, kill recaps and
 * statistics. Entries older than the retention window are discarded.
 */
public final class DamageTracker {

    /**
     * One damage event.
     *
     * @param attacker  responsible player (null for environment/mob damage)
     * @param source    display name of the attacker (player name or entity type)
     * @param damage    final damage applied
     * @param time      epoch millis
     * @param weapon    display name of the weapon or projectile type
     * @param cause     damage cause
     * @param critical  whether the hit was a critical hit
     */
    public record Hit(UUID attacker, String source, double damage, long time, String weapon, EntityDamageEvent.DamageCause cause,
                      boolean critical) {
    }

    private static final int MAX_HITS = 128;

    private final Map<UUID, Deque<Hit>> hits = new ConcurrentHashMap<>();
    private volatile long retentionMillis = 300_000;

    public void setRetention(long millis) {
        this.retentionMillis = Math.max(10_000, millis);
    }

    public void record(UUID victim, Hit hit) {
        Deque<Hit> deque = hits.computeIfAbsent(victim, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(hit);
            while (deque.size() > MAX_HITS) {
                deque.removeFirst();
            }
        }
    }

    /**
     * @return hits on the victim within the given window, oldest first
     */
    public List<Hit> recent(UUID victim, long windowMillis) {
        Deque<Hit> deque = hits.get(victim);
        if (deque == null) {
            return List.of();
        }
        long cutoff = System.currentTimeMillis() - windowMillis;
        List<Hit> out = new ArrayList<>();
        synchronized (deque) {
            for (Hit h : deque) {
                if (h.time() >= cutoff) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    /**
     * @return damage per attacking player within the window, highest first
     */
    public Map<UUID, Double> damageByPlayer(UUID victim, long windowMillis) {
        Map<UUID, Double> totals = new LinkedHashMap<>();
        for (Hit h : recent(victim, windowMillis)) {
            if (h.attacker() != null) {
                totals.merge(h.attacker(), h.damage(), Double::sum);
            }
        }
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(totals.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<UUID, Double> sorted = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> e : list) {
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted;
    }

    /**
     * @return the most recent player attacker within the window, or {@code null}
     */
    public UUID lastPlayerAttacker(UUID victim, long windowMillis) {
        List<Hit> list = recent(victim, windowMillis);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).attacker() != null) {
                return list.get(i).attacker();
            }
        }
        return null;
    }

    public long firstHitTime(UUID victim, long windowMillis) {
        List<Hit> list = recent(victim, windowMillis);
        return list.isEmpty() ? 0 : list.get(0).time();
    }

    public void clear(UUID victim) {
        hits.remove(victim);
    }

    public void purge() {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        hits.values().forEach(d -> {
            synchronized (d) {
                while (!d.isEmpty() && d.peekFirst().time() < cutoff) {
                    d.removeFirst();
                }
            }
        });
        hits.values().removeIf(d -> {
            synchronized (d) {
                return d.isEmpty();
            }
        });
    }
}
