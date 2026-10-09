package dev.ultimatecombat.core.util;

import dev.ultimatecombat.core.config.ConfigReader;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses potion effect lists written as {@code "EFFECT seconds level"} (level 1 = level I).
 */
public final class Effects {

    private Effects() {
    }

    public static List<PotionEffect> parse(ConfigReader r, String path) {
        List<PotionEffect> out = new ArrayList<>();
        for (String line : r.stringList(path)) {
            String[] parts = line.trim().split("\\s+");
            PotionEffectType type = parts.length > 0 ? Registries.potionEffect(parts[0]) : null;
            if (type == null) {
                r.warn(path, line, "Unknown effect (entry ignored)", "\"EFFECT seconds level\" such as \"REGENERATION 5 2\"", "entry skipped");
                continue;
            }
            try {
                double seconds = parts.length > 1 ? Double.parseDouble(parts[1]) : 10;
                int level = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
                if (seconds <= 0 || level < 1 || level > 255) {
                    throw new NumberFormatException();
                }
                out.add(new PotionEffect(type, (int) Math.round(seconds * 20), level - 1));
            } catch (NumberFormatException e) {
                r.warn(path, line, "Invalid duration or level (entry ignored)", "seconds > 0 and level 1-255", "entry skipped");
            }
        }
        return out;
    }
}
