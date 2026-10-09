package dev.ultimatecombat.core.kill;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything known about a player death, passed through every {@link KillHandler} in order.
 * Handlers may suppress rewards (anti-abuse) which later handlers must respect.
 */
public final class KillContext {

    private final Player victim;
    private final Player killer;
    private final Set<UUID> assists;
    private final Map<UUID, Double> damageByPlayer;
    private final EntityDamageEvent.DamageCause cause;
    private final ItemStack weapon;
    private final Location location;
    private final PlayerDeathEvent event;
    private final boolean combatLogDeath;
    private final long fightStart;
    private boolean rewardsSuppressed;
    private boolean pointsSuppressed;
    private boolean statsSuppressed;
    private String suppressionReason;
    private int victimStreakBefore;
    private int killerStreakAfter;
    private long killerPointsChange;
    private long victimPointsChange;
    private double moneyTransferred;

    public KillContext(Player victim, Player killer, Set<UUID> assists, Map<UUID, Double> damageByPlayer,
                       EntityDamageEvent.DamageCause cause, ItemStack weapon, Location location, PlayerDeathEvent event,
                       boolean combatLogDeath, long fightStart) {
        this.victim = victim;
        this.killer = killer;
        this.assists = new LinkedHashSet<>(assists);
        this.damageByPlayer = damageByPlayer;
        this.cause = cause;
        this.weapon = weapon;
        this.location = location;
        this.event = event;
        this.combatLogDeath = combatLogDeath;
        this.fightStart = fightStart;
    }

    public Player victim() {
        return victim;
    }

    /**
     * @return the credited killer or {@code null} for non-PvP deaths
     */
    public Player killer() {
        return killer;
    }

    public boolean isPvp() {
        return killer != null && killer != victim;
    }

    public Set<UUID> assists() {
        return assists;
    }

    public Map<UUID, Double> damageByPlayer() {
        return damageByPlayer;
    }

    public EntityDamageEvent.DamageCause cause() {
        return cause;
    }

    /**
     * @return snapshot of the killer's weapon at death time (may be air/null)
     */
    public ItemStack weapon() {
        return weapon;
    }

    public Location location() {
        return location;
    }

    /**
     * @return the death event, or {@code null} for synthetic combat-log punishments without a death
     */
    public PlayerDeathEvent event() {
        return event;
    }

    public boolean combatLogDeath() {
        return combatLogDeath;
    }

    public long fightStart() {
        return fightStart;
    }

    public void suppressRewards(String reason) {
        this.rewardsSuppressed = true;
        if (suppressionReason == null) {
            suppressionReason = reason;
        }
    }

    public void suppressPoints(String reason) {
        this.pointsSuppressed = true;
        if (suppressionReason == null) {
            suppressionReason = reason;
        }
    }

    public void suppressStats(String reason) {
        this.statsSuppressed = true;
        if (suppressionReason == null) {
            suppressionReason = reason;
        }
    }

    public boolean rewardsSuppressed() {
        return rewardsSuppressed;
    }

    public boolean pointsSuppressed() {
        return pointsSuppressed;
    }

    public boolean statsSuppressed() {
        return statsSuppressed;
    }

    public String suppressionReason() {
        return suppressionReason;
    }

    public int victimStreakBefore() {
        return victimStreakBefore;
    }

    public void victimStreakBefore(int value) {
        this.victimStreakBefore = value;
    }

    public int killerStreakAfter() {
        return killerStreakAfter;
    }

    public void killerStreakAfter(int value) {
        this.killerStreakAfter = value;
    }

    public long killerPointsChange() {
        return killerPointsChange;
    }

    public void killerPointsChange(long value) {
        this.killerPointsChange = value;
    }

    public long victimPointsChange() {
        return victimPointsChange;
    }

    public void victimPointsChange(long value) {
        this.victimPointsChange = value;
    }

    public double moneyTransferred() {
        return moneyTransferred;
    }

    public void moneyTransferred(double value) {
        this.moneyTransferred = value;
    }
}
