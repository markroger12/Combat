package dev.ultimatecombat.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player's kill streak increases, or ends (newStreak == 0).
 */
public final class KillstreakEvent extends UltimateCombatEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int previousStreak;
    private final int newStreak;

    public KillstreakEvent(Player player, int previousStreak, int newStreak) {
        this.player = player;
        this.previousStreak = previousStreak;
        this.newStreak = newStreak;
    }

    public Player getPlayer() {
        return player;
    }

    public int getPreviousStreak() {
        return previousStreak;
    }

    public int getNewStreak() {
        return newStreak;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
