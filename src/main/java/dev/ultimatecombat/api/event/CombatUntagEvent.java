package dev.ultimatecombat.api.event;

import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.UntagReason;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a player left combat.
 */
public final class CombatUntagEvent extends UltimateCombatEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final CombatType type;
    private final UntagReason reason;

    public CombatUntagEvent(Player player, CombatType type, UntagReason reason) {
        this.player = player;
        this.type = type;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public CombatType getType() {
        return type;
    }

    public UntagReason getReason() {
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
