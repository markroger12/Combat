package dev.ultimatecombat.core.integration;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Region / claim / land protection provider.
 */
public interface ProtectionHook {

    /**
     * @return whether PvP is allowed at the location according to this plugin
     */
    boolean isPvpAllowed(Location location);

    /**
     * @return whether the attacker may hurt the victim (claims, nations, trust lists ...)
     */
    default boolean canAttack(Player attacker, Player victim) {
        return isPvpAllowed(attacker.getLocation()) && isPvpAllowed(victim.getLocation());
    }

    /**
     * @return names of the regions/claims at the location (used for region-based exclusions)
     */
    default java.util.Set<String> regionsAt(Location location) {
        return java.util.Set.of();
    }
}
