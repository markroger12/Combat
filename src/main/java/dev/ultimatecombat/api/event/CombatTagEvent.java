package dev.ultimatecombat.api.event;

import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.TagCause;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired before a player becomes combat tagged or before an existing tag is refreshed.
 * Cancelling prevents the tag (or refresh). The duration may be changed.
 */
public final class CombatTagEvent extends UltimateCombatEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Entity opponent;
    private final CombatType type;
    private final TagCause cause;
    private final boolean refresh;
    private long durationMillis;
    private boolean cancelled;

    public CombatTagEvent(Player player, @Nullable Entity opponent, CombatType type, TagCause cause, boolean refresh, long durationMillis) {
        this.player = player;
        this.opponent = opponent;
        this.type = type;
        this.cause = cause;
        this.refresh = refresh;
        this.durationMillis = durationMillis;
    }

    public Player getPlayer() {
        return player;
    }

    public @Nullable Entity getOpponent() {
        return opponent;
    }

    public CombatType getType() {
        return type;
    }

    public TagCause getCause() {
        return cause;
    }

    /**
     * @return {@code true} if the player already was in combat and the timer is refreshed
     */
    public boolean isRefresh() {
        return refresh;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = Math.max(0, durationMillis);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
