package dev.ultimatecombat.core.player;

import java.util.UUID;

/**
 * Persistent per-player state (PvP toggle, grace protection, statistics). Volatile fields are
 * written by game threads and read by the database thread when saving.
 */
public final class PlayerProfile {

    private final UUID uuid;
    private volatile String name;
    private volatile Boolean pvpEnabled;
    private volatile Boolean pvpForced;
    private volatile long pvpCooldownUntil;
    private volatile long graceRemaining;
    private volatile boolean graceActive;
    private volatile boolean graceInitialized;
    private volatile String ipHash;
    private volatile long firstJoin;
    private volatile long lastSeen;
    private volatile boolean existsInDatabase;
    private volatile boolean loadFailed;
    private volatile boolean dirty;
    private volatile boolean statsReset;
    private final PlayerStats stats = new PlayerStats();

    public PlayerProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.firstJoin = System.currentTimeMillis();
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (name != null && !name.equals(this.name)) {
            this.name = name;
            dirty = true;
        }
    }

    public PlayerStats stats() {
        return stats;
    }

    /**
     * @return the player's own PvP preference, or {@code null} if never changed (use the default)
     */
    public Boolean pvpEnabled() {
        return pvpEnabled;
    }

    public void pvpEnabled(Boolean value) {
        this.pvpEnabled = value;
        dirty = true;
    }

    /**
     * @return an administrator-forced PvP state, or {@code null} if not forced
     */
    public Boolean pvpForced() {
        return pvpForced;
    }

    public void pvpForced(Boolean value) {
        this.pvpForced = value;
        dirty = true;
    }

    public long pvpCooldownUntil() {
        return pvpCooldownUntil;
    }

    public void pvpCooldownUntil(long value) {
        this.pvpCooldownUntil = value;
        dirty = true;
    }

    public long graceRemaining() {
        return graceRemaining;
    }

    public void graceRemaining(long value) {
        this.graceRemaining = Math.max(0, value);
        dirty = true;
    }

    public boolean graceActive() {
        return graceActive;
    }

    public void graceActive(boolean value) {
        this.graceActive = value;
        dirty = true;
    }

    public boolean graceInitialized() {
        return graceInitialized;
    }

    public void graceInitialized(boolean value) {
        this.graceInitialized = value;
        dirty = true;
    }

    public String ipHash() {
        return ipHash;
    }

    public void ipHash(String value) {
        if (value != null && !value.equals(ipHash)) {
            this.ipHash = value;
            dirty = true;
        }
    }

    public long firstJoin() {
        return firstJoin;
    }

    public void firstJoin(long value) {
        this.firstJoin = value;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void lastSeen(long value) {
        this.lastSeen = value;
        dirty = true;
    }

    public boolean existsInDatabase() {
        return existsInDatabase;
    }

    public void existsInDatabase(boolean value) {
        this.existsInDatabase = value;
    }

    /**
     * When loading failed the profile must not overwrite stored state (stat deltas are still safe).
     */
    public boolean loadFailed() {
        return loadFailed;
    }

    public void loadFailed(boolean value) {
        this.loadFailed = value;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean statsReset() {
        return statsReset;
    }

    public void statsReset(boolean value) {
        this.statsReset = value;
    }
}
