package dev.ultimatecombat.modules.borders;

import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable snapshot of borders.yml.
 */
public record BorderSettings(boolean visualBlocks, boolean pushback, Material material, int radius, int height,
                             boolean onlyReplaceable, long updateTicks, double pushStrength, double pushVertical,
                             boolean blockEnderPearls, boolean blockTeleports, boolean particles, String particlePreset,
                             String sound, boolean message, List<String> escapeCommands, long escapeCommandCooldownMillis,
                             Set<CombatType> combatTypes, boolean useProtectionPlugins, Set<String> ignoredRegions,
                             List<Zone> zones, WorldRules worlds) {

    /**
     * A built-in no-PvP cuboid.
     */
    public record Zone(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean blockPvp) {

        public boolean contains(String worldName, int x, int y, int z) {
            return world.equalsIgnoreCase(worldName) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public static BorderSettings parse(ConfigReader r, int maxRadius) {
        String mode = r.choice("mode", "both", "visual-blocks", "pushback", "both");
        List<Zone> zones = new ArrayList<>();
        for (String name : r.keys("zones")) {
            ConfigReader z = r.section("zones." + name);
            int[] min = coords(z, "min");
            int[] max = coords(z, "max");
            if (min == null || max == null) {
                continue;
            }
            zones.add(new Zone(name, z.string("world", "world"), Math.min(min[0], max[0]), Math.min(min[1], max[1]),
                    Math.min(min[2], max[2]), Math.max(min[0], max[0]), Math.max(min[1], max[1]), Math.max(min[2], max[2]),
                    z.bool("block-pvp", true)));
        }
        Set<CombatType> types = r.has("combat-types") ? r.enumSet("combat-types", CombatType.class) : EnumSet.of(CombatType.PVP);
        Set<String> ignored = new java.util.HashSet<>();
        for (String s : r.stringList("ignored-regions")) {
            ignored.add(s.toLowerCase(Locale.ROOT));
        }
        return new BorderSettings(
                !mode.equals("pushback"),
                !mode.equals("visual-blocks"),
                r.material("visual.material", Material.RED_STAINED_GLASS),
                Math.min(maxRadius, r.integer("visual.radius", 8, 2, 32)),
                r.integer("visual.height", 3, 1, 16),
                r.bool("visual.only-replace-air", true),
                r.ticks("visual.update-interval-ticks", 5, 1, 100),
                r.decimal("pushback.strength", 1.0, 0, 5),
                r.decimal("pushback.vertical", 0.25, 0, 2),
                r.bool("block-ender-pearls", true),
                r.bool("block-teleports", true),
                r.bool("particles.enabled", false),
                r.string("particles.preset", "border"),
                r.string("sound", "border-push"),
                r.bool("message", true),
                r.stringList("escape-attempt.commands"),
                r.durationMillis("escape-attempt.cooldown-seconds", 10_000, ConfigReader.TimeUnitHint.SECONDS, 0, 3_600_000),
                types,
                r.bool("use-protection-plugins", true),
                ignored,
                zones,
                WorldRules.read(r, "worlds"));
    }

    private static int[] coords(ConfigReader z, String path) {
        String raw = z.string(path, "");
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            z.warn(path, raw, "Invalid coordinates (zone ignored)", "x,y,z for example \"-50,0,-50\"", "zone skipped");
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException e) {
            z.warn(path, raw, "Invalid coordinates (zone ignored)", "x,y,z whole numbers", "zone skipped");
            return null;
        }
    }
}
