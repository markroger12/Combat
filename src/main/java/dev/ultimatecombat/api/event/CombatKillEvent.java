package dev.ultimatecombat.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * Fired after UltimateCombat processed a player death (stats, streaks, rewards).
 * {@link #isRewarded()} is {@code false} when rewards were suppressed (kill farming, combat-log
 * death, same IP ...); other reward plugins should respect that flag.
 */
public final class CombatKillEvent extends UltimateCombatEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final Player killer;
    private final Set<UUID> assists;
    private final boolean combatLogDeath;
    private final boolean rewarded;
    private final String suppressionReason;

    public CombatKillEvent(Player victim, @Nullable Player killer, Set<UUID> assists, boolean combatLogDeath, boolean rewarded,
                           @Nullable String suppressionReason) {
        this.victim = victim;
        this.killer = killer;
        this.assists = Set.copyOf(assists);
        this.combatLogDeath = combatLogDeath;
        this.rewarded = rewarded;
        this.suppressionReason = suppressionReason;
    }

    public Player getVictim() {
        return victim;
    }

    public @Nullable Player getKiller() {
        return killer;
    }

    public Set<UUID> getAssists() {
        return assists;
    }

    /**
     * @return {@code true} when the death was caused by the combat-log punishment
     */
    public boolean isCombatLogDeath() {
        return combatLogDeath;
    }

    public boolean isRewarded() {
        return rewarded;
    }

    public @Nullable String getSuppressionReason() {
        return suppressionReason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
