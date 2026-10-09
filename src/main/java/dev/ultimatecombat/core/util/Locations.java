package dev.ultimatecombat.core.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

/**
 * Text encoding and formatting helpers for locations.
 */
public final class Locations {

    private Locations() {
    }

    /**
     * @return {@code world;x;y;z;yaw;pitch}
     */
    public static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%s;%.3f;%.3f;%.3f;%.2f;%.2f", loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }

    public static Location decode(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] p = text.split(";");
        if (p.length < 4) {
            return null;
        }
        World world = Bukkit.getWorld(p[0]);
        if (world == null) {
            return null;
        }
        try {
            float yaw = p.length > 4 ? Float.parseFloat(p[4]) : 0f;
            float pitch = p.length > 5 ? Float.parseFloat(p[5]) : 0f;
            return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String format(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "?";
        }
        return loc.getWorld().getName() + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    public static boolean sameWorld(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    public static double distanceSquared(Location a, Location b) {
        if (!sameWorld(a, b)) {
            return Double.MAX_VALUE;
        }
        return a.distanceSquared(b);
    }
}
