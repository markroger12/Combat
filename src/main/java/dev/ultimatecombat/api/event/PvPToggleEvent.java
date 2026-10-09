package dev.ultimatecombat.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired before a player's PvP state changes (by the player or an administrator).
 */
public final class PvPToggleEvent extends UltimateCombatEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean newState;
    private final boolean byAdmin;
    private boolean cancelled;

    public PvPToggleEvent(Player player, boolean newState, boolean byAdmin) {
        this.player = player;
        this.newState = newState;
        this.byAdmin = byAdmin;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean getNewState() {
        return newState;
    }

    public boolean isByAdmin() {
        return byAdmin;
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
