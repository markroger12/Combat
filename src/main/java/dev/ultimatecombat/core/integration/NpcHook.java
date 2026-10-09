package dev.ultimatecombat.core.integration;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Player-like NPC provider (Citizens, optionally with Sentinel AI).
 */
public interface NpcHook {

    /**
     * Spawns an NPC that looks like the player (skin, armour, held item).
     *
     * @return handle or {@code null} if spawning failed
     */
    Handle spawn(Player template, Location location, String name, boolean fightBack);

    interface Handle {
        Entity entity();

        boolean isSpawned();

        void destroy();
    }
}
