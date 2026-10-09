package dev.ultimatecombat.modules.ghosts;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

/**
 * Paper 1.21.9+ mannequin support (a player-shaped entity with the player's skin). Only loaded
 * after the server confirmed that the Mannequin API exists.
 */
final class MannequinBridge {

    private MannequinBridge() {
    }

    static boolean available() {
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static Entity spawn(Location location, Player template) {
        return location.getWorld().spawn(location, Mannequin.class, m -> {
            try {
                m.setProfile(ResolvableProfile.resolvableProfile(template.getPlayerProfile()));
            } catch (RuntimeException | LinkageError ignored) {
                // default skin
            }
            m.setImmovable(false);
        });
    }
}
