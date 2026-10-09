package dev.ultimatecombat.modules.combat;

import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import org.bukkit.boss.BossBar;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime state of one tagged player.
 */
public final class CombatTag {

    private final UUID player;
    private volatile CombatType type;
    private volatile long startedAt;
    private volatile long expiresAt;
    private volatile long duration;
    private final Map<UUID, Long> opponents = new ConcurrentHashMap<>();
    private volatile UUID lastOpponent;
    private volatile String lastOpponentName = "";
    private volatile String pveSource = "";
    volatile BossBar bossBar;
    volatile boolean glowingApplied;
    volatile boolean wasGlowing;
    volatile ScheduledTask ticker;

    CombatTag(UUID player, CombatType type, long duration) {
        this.player = player;
        this.type = type;
        this.startedAt = System.currentTimeMillis();
        this.duration = duration;
        this.expiresAt = startedAt + duration;
    }

    public UUID player() {
        return player;
    }

    public CombatType type() {
        return type;
    }

    void type(CombatType type) {
        this.type = type;
    }

    public long startedAt() {
        return startedAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public long duration() {
        return duration;
    }

    void refresh(long newDuration) {
        this.duration = newDuration;
        this.expiresAt = System.currentTimeMillis() + newDuration;
    }

    /**
     * Extends the tag only if the new end is later than the current one.
     */
    void extendTo(long newDuration) {
        long candidate = System.currentTimeMillis() + newDuration;
        if (candidate > expiresAt) {
            this.duration = newDuration;
            this.expiresAt = candidate;
        }
    }

    public long remainingMillis() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    public double progress() {
        return duration <= 0 ? 0 : Math.max(0, Math.min(1, (double) remainingMillis() / duration));
    }

    public Set<UUID> opponents() {
        return opponents.keySet();
    }

    /**
     * @return last hit time per opponent
     */
    public Map<UUID, Long> opponentHits() {
        return opponents;
    }

    void addOpponent(UUID opponent, String name) {
        opponents.put(opponent, System.currentTimeMillis());
        lastOpponent = opponent;
        lastOpponentName = name;
    }

    void removeOpponent(UUID opponent) {
        opponents.remove(opponent);
    }

    public UUID lastOpponent() {
        return lastOpponent;
    }

    public String lastOpponentName() {
        return lastOpponentName;
    }

    public String pveSource() {
        return pveSource;
    }

    void pveSource(String name) {
        this.pveSource = name;
        this.lastOpponentName = name;
    }
}
