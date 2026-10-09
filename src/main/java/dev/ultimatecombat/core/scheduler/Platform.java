package dev.ultimatecombat.core.scheduler;

import org.bukkit.Bukkit;

/**
 * Detects the server software the plugin runs on. Detection is class-based so it never
 * depends on server brand strings that forks may change.
 */
public enum Platform {
    FOLIA,
    PAPER,
    SPIGOT;

    private static final Platform CURRENT = detect();

    public static Platform current() {
        return CURRENT;
    }

    public boolean isFolia() {
        return this == FOLIA;
    }

    public boolean isPaperBased() {
        return this == PAPER || this == FOLIA;
    }

    private static Platform detect() {
        if (classExists("io.papermc.paper.threadedregions.RegionizedServer")) {
            return FOLIA;
        }
        if (classExists("com.destroystokyo.paper.PaperConfig") || classExists("io.papermc.paper.configuration.Configuration")) {
            return PAPER;
        }
        return SPIGOT;
    }

    public static boolean classExists(String name) {
        try {
            Class.forName(name, false, Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * @return the Minecraft version reported by the server, for example {@code 1.21.11}.
     */
    public static String minecraftVersion() {
        String bukkit = Bukkit.getBukkitVersion();
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
    }
}
