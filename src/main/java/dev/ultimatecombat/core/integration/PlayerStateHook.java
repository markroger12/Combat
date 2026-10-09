package dev.ultimatecombat.core.integration;

import org.bukkit.entity.Player;

/**
 * Vanish, god mode and disguise state from plugins such as EssentialsX, CMI or LibsDisguises.
 * Every method has a safe default so hooks only implement what their plugin supports.
 */
public interface PlayerStateHook {

    default boolean isVanished(Player player) {
        return false;
    }

    default void setVanished(Player player, boolean vanished) {
    }

    default boolean isGodMode(Player player) {
        return false;
    }

    default void setGodMode(Player player, boolean enabled) {
    }

    default boolean isDisguised(Player player) {
        return false;
    }

    default void undisguise(Player player) {
    }
}
