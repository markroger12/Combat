package dev.ultimatecombat.core.integration;

import org.bukkit.entity.Player;

/**
 * External placeholder resolver (PlaceholderAPI).
 */
public interface PlaceholderHook {

    String apply(Player player, String text);
}
