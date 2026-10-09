package dev.ultimatecombat.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a player's new-player protection started or ended.
 */
public final class GraceChangeEvent extends UltimateCombatEvent {

    public enum Reason { FIRST_JOIN, ADMIN_GIVE, ADMIN_REMOVE, EXPIRED, VOLUNTARY, ATTACKED_SOMEONE, COMMAND_USED }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean started;
    private final Reason reason;

    public GraceChangeEvent(Player player, boolean started, Reason reason) {
        this.player = player;
        this.started = started;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isStarted() {
        return started;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
