package dev.ultimatecombat.api.event;

import dev.ultimatecombat.api.CombatType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a combat-tagged player disconnects, before any punishment is applied.
 * Cancelling skips every punishment.
 */
public final class CombatLogEvent extends UltimateCombatEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final CombatType type;
    private final String profile;
    private final String quitReason;
    private boolean cancelled;

    public CombatLogEvent(Player player, CombatType type, String profile, String quitReason) {
        this.player = player;
        this.type = type;
        this.profile = profile;
        this.quitReason = quitReason;
    }

    public Player getPlayer() {
        return player;
    }

    public CombatType getType() {
        return type;
    }

    /**
     * @return name of the punishment profile from combatlog.yml
     */
    public String getProfile() {
        return profile;
    }

    /**
     * @return {@code quit}, {@code kick} or {@code timeout}
     */
    public String getQuitReason() {
        return quitReason;
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
