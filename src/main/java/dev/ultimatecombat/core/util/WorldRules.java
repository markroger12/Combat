package dev.ultimatecombat.core.util;

import dev.ultimatecombat.core.config.ConfigReader;
import org.bukkit.World;

import java.util.Locale;
import java.util.Set;

/**
 * World filter used by every feature: a mode (blacklist/whitelist) plus a list of world names.
 *
 * <pre>
 * worlds:
 *   mode: blacklist     # blacklist = active everywhere except the listed worlds
 *                       # whitelist = active only in the listed worlds
 *   list: []            # empty blacklist = every world; empty whitelist = no world
 * </pre>
 */
public record WorldRules(boolean whitelist, Set<String> worlds) {

    public static final WorldRules ALL = new WorldRules(false, Set.of());

    public static WorldRules read(ConfigReader r, String path) {
        if (!r.has(path)) {
            return ALL;
        }
        ConfigReader s = r.section(path);
        String mode = s.choice("mode", "blacklist", "blacklist", "whitelist");
        return new WorldRules(mode.equals("whitelist"), s.lowerCaseSet("list"));
    }

    public boolean allows(World world) {
        return world != null && allows(world.getName());
    }

    public boolean allows(String world) {
        boolean listed = worlds.contains(world.toLowerCase(Locale.ROOT));
        return whitelist == listed;
    }
}
