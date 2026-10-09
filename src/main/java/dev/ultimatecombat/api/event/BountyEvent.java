package dev.ultimatecombat.api.event;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired for bounty life-cycle changes. {@link Action#PLACE} is cancellable (fired before money moves).
 */
public final class BountyEvent extends UltimateCombatEvent implements Cancellable {

    public enum Action { PLACE, CLAIM, EXPIRE, REMOVE }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Action action;
    private final OfflinePlayer target;
    private final Player actor;
    private final double amount;
    private boolean cancelled;

    public BountyEvent(Action action, OfflinePlayer target, @Nullable Player actor, double amount) {
        this.action = action;
        this.target = target;
        this.actor = actor;
        this.amount = amount;
    }

    public Action getAction() {
        return action;
    }

    public OfflinePlayer getTarget() {
        return target;
    }

    /**
     * @return the player placing (PLACE) or claiming (CLAIM) the bounty, if any
     */
    public @Nullable Player getActor() {
        return actor;
    }

    public double getAmount() {
        return amount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        if (action == Action.PLACE) {
            this.cancelled = cancel;
        }
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
