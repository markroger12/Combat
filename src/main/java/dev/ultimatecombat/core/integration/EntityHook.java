package dev.ultimatecombat.core.integration;

import org.bukkit.entity.Entity;

/**
 * Custom mob provider (MythicMobs, ...).
 */
public interface EntityHook {

    /**
     * @return the custom mob id (for example {@code SkeletonKing}) or {@code null} for vanilla mobs
     */
    String customId(Entity entity);

    /**
     * @return whether the custom mob is considered a boss by its plugin
     */
    default boolean isBoss(Entity entity) {
        return false;
    }
}
