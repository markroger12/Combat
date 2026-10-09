package dev.ultimatecombat.modules.corpses;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;

/**
 * Lying player-shaped corpses (Paper 1.21.9+). Only loaded after the Mannequin API was detected.
 */
final class CorpseMannequins {

    private CorpseMannequins() {
    }

    static Entity spawn(Location location, Player template) {
        return location.getWorld().spawn(location, Mannequin.class, m -> {
            try {
                m.setProfile(ResolvableProfile.resolvableProfile(template.getPlayerProfile()));
            } catch (RuntimeException | LinkageError ignored) {
                // default skin
            }
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setGravity(false);
            m.setAI(false);
            try {
                m.setPose(Pose.SLEEPING, true);
            } catch (RuntimeException | LinkageError ignored) {
                // pose not supported: stands upright
            }
            EntityEquipment src = template.getEquipment();
            EntityEquipment eq = m.getEquipment();
            if (src != null) {
                eq.setHelmet(clone(src.getHelmet()));
                eq.setChestplate(clone(src.getChestplate()));
                eq.setLeggings(clone(src.getLeggings()));
                eq.setBoots(clone(src.getBoots()));
            }
        });
    }

    private static org.bukkit.inventory.ItemStack clone(org.bukkit.inventory.ItemStack item) {
        return item == null ? null : item.clone();
    }
}
