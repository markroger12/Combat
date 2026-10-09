package dev.ultimatecombat.core.player;

/**
 * PvP statistics of one player. Counters are persisted as <em>deltas</em> ({@code kills = kills + ?})
 * so that several servers sharing one database never overwrite each other's updates.
 * All methods are synchronized; instances are shared between game threads and the database thread.
 */
public final class PlayerStats {

    public enum Stat {
        KILLS, DEATHS, COMBAT_LOGS, POINTS, STREAK, BEST_STREAK, ASSISTS, MOB_KILLS, BOUNTIES_CLAIMED,
        BOUNTY_EARNINGS, DAMAGE_DEALT, DAMAGE_TAKEN, KDR
    }

    private int kills;
    private int deaths;
    private int combatLogs;
    private long points;
    private int streak;
    private int bestStreak;
    private int assists;
    private int mobKills;
    private int bountiesClaimed;
    private double bountyEarnings;
    private double damageDealt;
    private double damageTaken;

    private Delta pending = new Delta();
    private boolean streakDirty;

    /**
     * Unsaved changes.
     */
    public static final class Delta {
        public int kills;
        public int deaths;
        public int combatLogs;
        public long points;
        public int assists;
        public int mobKills;
        public int bountiesClaimed;
        public double bountyEarnings;
        public double damageDealt;
        public double damageTaken;
        public Integer streak;
        public int bestStreak;

        public boolean isEmpty() {
            return kills == 0 && deaths == 0 && combatLogs == 0 && points == 0 && assists == 0 && mobKills == 0
                    && bountiesClaimed == 0 && bountyEarnings == 0 && damageDealt == 0 && damageTaken == 0 && streak == null;
        }

        void merge(Delta other) {
            kills += other.kills;
            deaths += other.deaths;
            combatLogs += other.combatLogs;
            points += other.points;
            assists += other.assists;
            mobKills += other.mobKills;
            bountiesClaimed += other.bountiesClaimed;
            bountyEarnings += other.bountyEarnings;
            damageDealt += other.damageDealt;
            damageTaken += other.damageTaken;
            if (streak == null) {
                streak = other.streak;
            }
            bestStreak = Math.max(bestStreak, other.bestStreak);
        }
    }

    public synchronized void load(int kills, int deaths, int combatLogs, long points, int streak, int bestStreak, int assists,
                                  int mobKills, int bountiesClaimed, double bountyEarnings, double damageDealt, double damageTaken) {
        this.kills = kills + pending.kills;
        this.deaths = deaths + pending.deaths;
        this.combatLogs = combatLogs + pending.combatLogs;
        this.points = points + pending.points;
        this.streak = pending.streak != null ? pending.streak : streak;
        this.bestStreak = Math.max(bestStreak, pending.bestStreak);
        this.assists = assists + pending.assists;
        this.mobKills = mobKills + pending.mobKills;
        this.bountiesClaimed = bountiesClaimed + pending.bountiesClaimed;
        this.bountyEarnings = bountyEarnings + pending.bountyEarnings;
        this.damageDealt = damageDealt + pending.damageDealt;
        this.damageTaken = damageTaken + pending.damageTaken;
    }

    public synchronized void addKill() {
        kills++;
        pending.kills++;
    }

    public synchronized void addDeath() {
        deaths++;
        pending.deaths++;
    }

    public synchronized void addCombatLog() {
        combatLogs++;
        pending.combatLogs++;
    }

    public synchronized void addAssist() {
        assists++;
        pending.assists++;
    }

    public synchronized void addMobKill() {
        mobKills++;
        pending.mobKills++;
    }

    public synchronized void addBountyClaim(double amount) {
        bountiesClaimed++;
        bountyEarnings += amount;
        pending.bountiesClaimed++;
        pending.bountyEarnings += amount;
    }

    public synchronized void addPoints(long amount) {
        points += amount;
        pending.points += amount;
    }

    /**
     * Adds points but never lets the total drop below {@code floor}.
     *
     * @return the change actually applied
     */
    public synchronized long addPointsClamped(long amount, long floor) {
        long target = Math.max(floor, points + amount);
        long change = target - points;
        points = target;
        pending.points += change;
        return change;
    }

    public synchronized void addDamageDealt(double amount) {
        damageDealt += amount;
        pending.damageDealt += amount;
    }

    public synchronized void addDamageTaken(double amount) {
        damageTaken += amount;
        pending.damageTaken += amount;
    }

    /**
     * @return the new streak
     */
    public synchronized int incrementStreak() {
        streak++;
        pending.streak = streak;
        if (streak > bestStreak) {
            bestStreak = streak;
            pending.bestStreak = Math.max(pending.bestStreak, streak);
        }
        streakDirty = true;
        return streak;
    }

    /**
     * @return the streak before resetting
     */
    public synchronized int resetStreak() {
        int old = streak;
        streak = 0;
        pending.streak = 0;
        streakDirty = true;
        return old;
    }

    /**
     * Administrative reset of every value. Persisted as absolute values by the repository.
     */
    public synchronized void resetAll() {
        kills = deaths = combatLogs = streak = bestStreak = assists = mobKills = bountiesClaimed = 0;
        points = 0;
        bountyEarnings = damageDealt = damageTaken = 0;
        pending = new Delta();
    }

    public synchronized Delta drain() {
        Delta d = pending;
        pending = new Delta();
        streakDirty = false;
        return d;
    }

    /**
     * Puts a delta back after a failed save so nothing is lost.
     */
    public synchronized void restore(Delta delta) {
        delta.merge(pending);
        pending = delta;
    }

    public synchronized boolean isDirty() {
        return !pending.isEmpty() || streakDirty;
    }

    public synchronized int kills() {
        return kills;
    }

    public synchronized int deaths() {
        return deaths;
    }

    public synchronized int combatLogs() {
        return combatLogs;
    }

    public synchronized long points() {
        return points;
    }

    public synchronized int streak() {
        return streak;
    }

    public synchronized int bestStreak() {
        return bestStreak;
    }

    public synchronized int assists() {
        return assists;
    }

    public synchronized int mobKills() {
        return mobKills;
    }

    public synchronized int bountiesClaimed() {
        return bountiesClaimed;
    }

    public synchronized double bountyEarnings() {
        return bountyEarnings;
    }

    public synchronized double damageDealt() {
        return damageDealt;
    }

    public synchronized double damageTaken() {
        return damageTaken;
    }

    public synchronized double kdr() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    public synchronized double value(Stat stat) {
        return switch (stat) {
            case KILLS -> kills;
            case DEATHS -> deaths;
            case COMBAT_LOGS -> combatLogs;
            case POINTS -> points;
            case STREAK -> streak;
            case BEST_STREAK -> bestStreak;
            case ASSISTS -> assists;
            case MOB_KILLS -> mobKills;
            case BOUNTIES_CLAIMED -> bountiesClaimed;
            case BOUNTY_EARNINGS -> bountyEarnings;
            case DAMAGE_DEALT -> damageDealt;
            case DAMAGE_TAKEN -> damageTaken;
            case KDR -> kdr();
        };
    }
}
