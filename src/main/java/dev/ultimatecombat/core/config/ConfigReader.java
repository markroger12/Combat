package dev.ultimatecombat.core.config;

import dev.ultimatecombat.core.util.Registries;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed, validating view over a {@link ConfigFile}. Every getter receives the default that
 * matches the bundled documentation; invalid values are reported and replaced by that default.
 *
 * <p>Lookup order for a path: per-world overlay (when present) → administrator's file →
 * bundled default file → code default.</p>
 */
public final class ConfigReader {

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|t|s|m|h|d|w)?", Pattern.CASE_INSENSITIVE);

    private final ConfigFile file;
    private final String base;
    private final ConfigurationSection overlay;

    ConfigReader(ConfigFile file, String base, ConfigurationSection overlay) {
        this.file = file;
        this.base = base;
        this.overlay = overlay;
    }

    public ConfigFile file() {
        return file;
    }

    public String basePath() {
        return base;
    }

    public String full(String path) {
        if (path == null || path.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? path : base + "." + path;
    }

    public ConfigReader section(String path) {
        return new ConfigReader(file, full(path), overlay);
    }

    /**
     * @return a reader using the same file but with a per-world overlay applied.
     */
    public ConfigReader withOverlay(ConfigurationSection overlaySection) {
        return new ConfigReader(file, base, overlaySection);
    }

    // ------------------------------------------------------------------ raw access

    public Object raw(String path) {
        String full = full(path);
        if (overlay != null && overlay.isSet(full)) {
            return overlay.get(full);
        }
        YamlConfiguration user = file.user();
        if (user.isSet(full)) {
            return user.get(full);
        }
        return file.defaults().get(full);
    }

    public boolean has(String path) {
        String full = full(path);
        return (overlay != null && overlay.isSet(full)) || file.user().isSet(full) || file.defaults().isSet(full);
    }

    public boolean isSection(String path) {
        Object raw = raw(path);
        return raw instanceof ConfigurationSection;
    }

    /**
     * Keys of a map-like section. When the administrator defined the section, only their keys
     * are used (so deleting an entry really deletes it); otherwise the bundled defaults apply.
     */
    public Set<String> keys(String path) {
        String full = full(path);
        if (overlay != null && overlay.isConfigurationSection(full)) {
            return new LinkedHashSet<>(overlay.getConfigurationSection(full).getKeys(false));
        }
        YamlConfiguration user = file.user();
        if (user.isConfigurationSection(full)) {
            return new LinkedHashSet<>(user.getConfigurationSection(full).getKeys(false));
        }
        if (user.isSet(full) && !user.isConfigurationSection(full)) {
            Object value = user.get(full);
            if (value instanceof Map<?, ?> map && map.isEmpty() || value == null) {
                return Collections.emptySet();
            }
            warn(path, String.valueOf(value), "Expected a section (a list of named entries)", "a YAML section", "no entries");
            return Collections.emptySet();
        }
        YamlConfiguration def = file.defaults();
        if (def.isConfigurationSection(full)) {
            return new LinkedHashSet<>(def.getConfigurationSection(full).getKeys(false));
        }
        return Collections.emptySet();
    }

    public Set<String> keys() {
        return keys("");
    }

    public void warn(String path, String value, String problem, String expected, String fallback) {
        file.report(new ValidationIssue(file.name(), full(path), value, problem, expected, fallback, ValidationIssue.Severity.WARNING));
    }

    public void info(String path, String message) {
        file.report(new ValidationIssue(file.name(), full(path), null, message, null, null, ValidationIssue.Severity.INFO));
    }

    // ------------------------------------------------------------------ primitives

    public boolean bool(String path, boolean def) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = raw.toString().trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "true", "yes", "on", "enabled" -> {
                return true;
            }
            case "false", "no", "off", "disabled" -> {
                return false;
            }
            default -> {
                warn(path, raw.toString(), "Not a valid true/false value", "true or false", String.valueOf(def));
                return def;
            }
        }
    }

    public int integer(String path, int def) {
        return integer(path, def, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public int integer(String path, int def, int min, int max) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        long value;
        if (raw instanceof Number n) {
            if (n.doubleValue() != Math.rint(n.doubleValue())) {
                warn(path, raw.toString(), "Must be a whole number", range(min, max), String.valueOf(def));
                return def;
            }
            value = n.longValue();
        } else {
            try {
                value = Long.parseLong(raw.toString().trim());
            } catch (NumberFormatException e) {
                warn(path, raw.toString(), "Not a whole number", range(min, max), String.valueOf(def));
                return def;
            }
        }
        if (value < min || value > max) {
            warn(path, raw.toString(), "Out of the allowed range", range(min, max), String.valueOf(def));
            return def;
        }
        return (int) value;
    }

    public long longValue(String path, long def, long min, long max) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        long value;
        if (raw instanceof Number n) {
            value = n.longValue();
        } else {
            try {
                value = Long.parseLong(raw.toString().trim());
            } catch (NumberFormatException e) {
                warn(path, raw.toString(), "Not a whole number", range(min, max), String.valueOf(def));
                return def;
            }
        }
        if (value < min || value > max) {
            warn(path, raw.toString(), "Out of the allowed range", range(min, max), String.valueOf(def));
            return def;
        }
        return value;
    }

    public double decimal(String path, double def) {
        return decimal(path, def, -Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public double decimal(String path, double def, double min, double max) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        double value;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else {
            try {
                value = Double.parseDouble(raw.toString().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                warn(path, raw.toString(), "Not a number", rangeD(min, max), String.valueOf(def));
                return def;
            }
        }
        if (Double.isNaN(value) || value < min || value > max) {
            warn(path, raw.toString(), "Out of the allowed range", rangeD(min, max), String.valueOf(def));
            return def;
        }
        return value;
    }

    /**
     * Reads a percentage written as {@code 25}, {@code 25%} or {@code 0.25%}. Returns a fraction (0.25).
     */
    public double percent(String path, double defPercent) {
        Object raw = raw(path);
        if (raw == null) {
            return defPercent / 100.0;
        }
        String s = raw.toString().trim();
        if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (v < 0 || v > 100) {
                warn(path, raw.toString(), "Percentage out of range", "0-100", defPercent + "%");
                return defPercent / 100.0;
            }
            return v / 100.0;
        } catch (NumberFormatException e) {
            warn(path, raw.toString(), "Not a percentage", "a number between 0 and 100", defPercent + "%");
            return defPercent / 100.0;
        }
    }

    public String string(String path, String def) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof ConfigurationSection || raw instanceof List<?>) {
            warn(path, raw.toString(), "Expected a single text value", "text", def);
            return def;
        }
        return raw.toString();
    }

    /**
     * Reads one of a fixed set of options (case-insensitive).
     */
    public String choice(String path, String def, String... options) {
        String value = string(path, def);
        for (String option : options) {
            if (option.equalsIgnoreCase(value.trim())) {
                return option;
            }
        }
        warn(path, value, "Unknown option", String.join(", ", options), def);
        return def;
    }

    public List<String> stringList(String path) {
        Object raw = raw(path);
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        if (raw instanceof ConfigurationSection) {
            warn(path, "<section>", "Expected a list", "a YAML list (- entry)", "empty list");
            return Collections.emptyList();
        }
        String s = raw.toString();
        if (s.isEmpty()) {
            return Collections.emptyList();
        }
        return List.of(s);
    }

    public Set<String> lowerCaseSet(String path) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : stringList(path)) {
            out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    public <E extends Enum<E>> E enumValue(String path, Class<E> type, E def) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        String s = raw.toString().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(s)) {
                return constant;
            }
        }
        warn(path, raw.toString(), "Unknown value", enumOptions(type), def == null ? "none" : def.name());
        return def;
    }

    public <E extends Enum<E>> Set<E> enumSet(String path, Class<E> type) {
        EnumSet<E> out = EnumSet.noneOf(type);
        for (String entry : stringList(path)) {
            String s = entry.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (s.equals("*") || s.equals("ALL")) {
                out.addAll(EnumSet.allOf(type));
                continue;
            }
            boolean found = false;
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(s)) {
                    out.add(constant);
                    found = true;
                    break;
                }
            }
            if (!found) {
                warn(path, entry, "Unknown value in list (entry ignored)", enumOptions(type), "entry skipped");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ durations

    /**
     * Reads a duration. Plain numbers are interpreted in {@code defaultUnit}; text values may use
     * suffixes: ms, t (ticks), s, m, h, d, w and may be combined ("1h30m").
     *
     * @return the duration in milliseconds
     */
    public long durationMillis(String path, long defMillis, TimeUnitHint defaultUnit) {
        Object raw = raw(path);
        if (raw == null) {
            return defMillis;
        }
        if (raw instanceof Number n) {
            double v = n.doubleValue();
            if (v < 0) {
                warn(path, raw.toString(), "Durations cannot be negative", "0 or more", defMillis + "ms");
                return defMillis;
            }
            return (long) (v * defaultUnit.millis);
        }
        String s = raw.toString().trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) {
            return defMillis;
        }
        Matcher m = DURATION.matcher(s);
        long total = 0;
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) {
                break;
            }
            consumed = m.end();
            double amount = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            long factor = unit == null ? defaultUnit.millis : switch (unit) {
                case "ms" -> 1L;
                case "t" -> 50L;
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> defaultUnit.millis;
            };
            total += (long) (amount * factor);
        }
        if (consumed != s.length()) {
            warn(path, raw.toString(), "Not a valid duration", "a number (" + defaultUnit.label + ") or text like 30s, 5m, 1h30m", defMillis + "ms");
            return defMillis;
        }
        return total;
    }

    public long durationMillis(String path, long defMillis, TimeUnitHint defaultUnit, long minMillis, long maxMillis) {
        long value = durationMillis(path, defMillis, defaultUnit);
        if (value < minMillis || value > maxMillis) {
            warn(path, String.valueOf(raw(path)), "Duration out of the allowed range",
                    (minMillis / defaultUnit.millis) + "-" + (maxMillis / defaultUnit.millis) + " " + defaultUnit.label, defMillis + "ms");
            return defMillis;
        }
        return value;
    }

    public long ticks(String path, long defTicks, long minTicks, long maxTicks) {
        long millis = durationMillis(path, defTicks * 50L, TimeUnitHint.TICKS);
        long ticks = millis / 50L;
        if (ticks < minTicks || ticks > maxTicks) {
            warn(path, String.valueOf(raw(path)), "Out of the allowed range", minTicks + "-" + maxTicks + " ticks", defTicks + " ticks");
            return defTicks;
        }
        return ticks;
    }

    public enum TimeUnitHint {
        MILLIS(1L, "milliseconds"),
        TICKS(50L, "ticks"),
        SECONDS(1000L, "seconds"),
        MINUTES(60_000L, "minutes"),
        HOURS(3_600_000L, "hours"),
        DAYS(86_400_000L, "days");

        final long millis;
        final String label;

        TimeUnitHint(long millis, String label) {
            this.millis = millis;
            this.label = label;
        }
    }

    // ------------------------------------------------------------------ minecraft types

    public Material material(String path, Material def) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        Material m = Registries.material(raw.toString());
        if (m == null) {
            warn(path, raw.toString(), "Unknown material", "a material name such as DIAMOND_SWORD", def == null ? "none" : def.name());
            return def;
        }
        return m;
    }

    /**
     * Reads a list of materials. Supports wildcards: {@code *_SWORD}, {@code NETHERITE_*}, {@code *}.
     */
    public Set<Material> materialSet(String path) {
        return materialsFrom(path, stringList(path));
    }

    public Set<Material> materialsFrom(String path, Collection<String> entries) {
        EnumSet<Material> out = EnumSet.noneOf(Material.class);
        for (String entry : entries) {
            String e = entry.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
            if (e.contains("*")) {
                String regex = e.replace("*", ".*");
                boolean any = false;
                for (Material m : Material.values()) {
                    if (!Registries.isLegacy(m) && m.name().matches(regex)) {
                        out.add(m);
                        any = true;
                    }
                }
                if (!any) {
                    warn(path, entry, "Wildcard matches no material (entry ignored)", "for example *_SWORD", "entry skipped");
                }
                continue;
            }
            Material m = Registries.material(e);
            if (m == null) {
                warn(path, entry, "Unknown material in list (entry ignored)", "a material name such as DIAMOND_SWORD", "entry skipped");
            } else {
                out.add(m);
            }
        }
        return out;
    }

    public Set<EntityType> entityTypes(String path) {
        EnumSet<EntityType> out = EnumSet.noneOf(EntityType.class);
        for (String entry : stringList(path)) {
            String e = entry.trim();
            if (e.equals("*")) {
                out.addAll(EnumSet.allOf(EntityType.class));
                continue;
            }
            EntityType type = Registries.entityType(e);
            if (type == null) {
                warn(path, entry, "Unknown entity type (entry ignored)", "an entity type such as ZOMBIE", "entry skipped");
            } else {
                out.add(type);
            }
        }
        return out;
    }

    public PotionEffectType potionEffect(String path, PotionEffectType def) {
        Object raw = raw(path);
        if (raw == null) {
            return def;
        }
        PotionEffectType type = Registries.potionEffect(raw.toString());
        if (type == null) {
            warn(path, raw.toString(), "Unknown potion effect", "an effect such as SPEED or minecraft:speed", def == null ? "none" : Registries.key(def));
            return def;
        }
        return type;
    }

    public static String range(long min, long max) {
        if (min == Integer.MIN_VALUE && max == Integer.MAX_VALUE) {
            return "a whole number";
        }
        if (max == Integer.MAX_VALUE || max == Long.MAX_VALUE) {
            return "a whole number >= " + min;
        }
        return "a whole number " + min + "-" + max;
    }

    public static String rangeD(double min, double max) {
        if (min == -Double.MAX_VALUE && max == Double.MAX_VALUE) {
            return "a number";
        }
        if (max == Double.MAX_VALUE) {
            return "a number >= " + min;
        }
        return "a number " + min + "-" + max;
    }

    public static <E extends Enum<E>> String enumOptions(Class<E> type) {
        E[] constants = type.getEnumConstants();
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(constants.length, 12);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(constants[i].name());
        }
        if (constants.length > shown) {
            sb.append(", ...");
        }
        return sb.toString();
    }
}
