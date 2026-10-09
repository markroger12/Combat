package dev.ultimatecombat.core.integration;

import org.bukkit.entity.Player;

/**
 * Party / faction / team provider used for friendly-fire exclusions.
 */
public interface AllyHook {

    boolean areAllies(Player a, Player b);
}
