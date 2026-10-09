package dev.ultimatecombat.core.kill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * A step of the kill pipeline. Handlers run in ascending {@link #killPriority()} order:
 * <ol>
 *     <li>{@value #ANALYZE} - anti abuse decides whether rewards/points count</li>
 *     <li>{@value #STATS} - statistics</li>
 *     <li>{@value #STREAKS} - kill streaks</li>
 *     <li>{@value #ECONOMY} - points, money, bounties, heads</li>
 *     <li>{@value #REWARDS} - reward rules</li>
 *     <li>{@value #NOTIFY} - recaps, announcements</li>
 * </ol>
 */
public interface KillHandler {

    int ANALYZE = 0;
    int STATS = 100;
    int STREAKS = 200;
    int ECONOMY = 300;
    int REWARDS = 400;
    int NOTIFY = 500;

    int killPriority();

    /**
     * Called for every player death (PvP and non-PvP; check {@link KillContext#isPvp()}).
     */
    default void onPlayerDeath(KillContext context) {
    }

    /**
     * Called when a player killed a non-player entity.
     */
    default void onMobKill(Player killer, LivingEntity entity, EntityDeathEvent event) {
    }

    /**
     * @return whether this handler is currently active (module enabled)
     */
    default boolean activeHandler() {
        return true;
    }
}
