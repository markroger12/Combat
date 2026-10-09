package dev.ultimatecombat.core.util;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Version adapter for registry-backed game types. Several Bukkit types changed between
 * Minecraft versions (enum → interface, renamed constants), so the plugin never references
 * version-specific constants directly. Instead every lookup goes through namespaced keys with
 * legacy-name aliases, which works on every supported version.
 */
public final class Registries {

    private static final Map<String, String> EFFECT_ALIASES = Map.ofEntries(
            Map.entry("slow", "slowness"),
            Map.entry("fast_digging", "haste"),
            Map.entry("slow_digging", "mining_fatigue"),
            Map.entry("increase_damage", "strength"),
            Map.entry("heal", "instant_health"),
            Map.entry("harm", "instant_damage"),
            Map.entry("jump", "jump_boost"),
            Map.entry("confusion", "nausea"),
            Map.entry("damage_resistance", "resistance"));

    private static final Map<String, String> ENCHANT_ALIASES = Map.ofEntries(
            Map.entry("protection_environmental", "protection"),
            Map.entry("protection_fire", "fire_protection"),
            Map.entry("protection_fall", "feather_falling"),
            Map.entry("protection_explosions", "blast_protection"),
            Map.entry("protection_projectile", "projectile_protection"),
            Map.entry("oxygen", "respiration"),
            Map.entry("water_worker", "aqua_affinity"),
            Map.entry("damage_all", "sharpness"),
            Map.entry("damage_undead", "smite"),
            Map.entry("damage_arthropods", "bane_of_arthropods"),
            Map.entry("loot_bonus_mobs", "looting"),
            Map.entry("sweeping_edge", "sweeping_edge"),
            Map.entry("sweeping", "sweeping_edge"),
            Map.entry("dig_speed", "efficiency"),
            Map.entry("durability", "unbreaking"),
            Map.entry("loot_bonus_blocks", "fortune"),
            Map.entry("arrow_damage", "power"),
            Map.entry("arrow_knockback", "punch"),
            Map.entry("arrow_fire", "flame"),
            Map.entry("arrow_infinite", "infinity"),
            Map.entry("luck", "luck_of_the_sea"));

    private static final Map<String, String> ENTITY_ALIASES = Map.ofEntries(
            Map.entry("primed_tnt", "tnt"),
            Map.entry("ender_crystal", "end_crystal"),
            Map.entry("snowman", "snow_golem"),
            Map.entry("mushroom_cow", "mooshroom"),
            Map.entry("fishing_hook", "fishing_bobber"),
            Map.entry("lightning", "lightning_bolt"),
            Map.entry("leash_hitch", "leash_knot"),
            Map.entry("thrown_exp_bottle", "experience_bottle"),
            Map.entry("dropped_item", "item"),
            Map.entry("splash_potion", "potion"),
            Map.entry("ender_signal", "eye_of_ender"),
            Map.entry("firework", "firework_rocket"),
            Map.entry("minecart_tnt", "tnt_minecart"));

    private static final Map<String, String> PARTICLE_ALIASES = Map.ofEntries(
            Map.entry("redstone", "dust"),
            Map.entry("villager_happy", "happy_villager"),
            Map.entry("villager_angry", "angry_villager"),
            Map.entry("spell_mob", "entity_effect"),
            Map.entry("spell_witch", "witch"),
            Map.entry("spell", "effect"),
            Map.entry("spell_instant", "instant_effect"),
            Map.entry("crit_magic", "enchanted_hit"),
            Map.entry("explosion_normal", "poof"),
            Map.entry("explosion_large", "explosion"),
            Map.entry("explosion_huge", "explosion_emitter"),
            Map.entry("fireworks_spark", "firework"),
            Map.entry("water_drop", "rain"),
            Map.entry("smoke_normal", "smoke"),
            Map.entry("smoke_large", "large_smoke"),
            Map.entry("drip_lava", "dripping_lava"),
            Map.entry("drip_water", "dripping_water"),
            Map.entry("block_crack", "block"),
            Map.entry("item_crack", "item"),
            Map.entry("slime", "item_slime"),
            Map.entry("snowball", "item_snowball"),
            Map.entry("totem", "totem_of_undying"),
            Map.entry("town_aura", "mycelium"),
            Map.entry("enchantment_table", "enchant"),
            Map.entry("suspended_depth", "underwater"));

    private static volatile Map<String, String> soundNames;
    private static volatile Map<String, EntityType> entityTypes;
    private static volatile Map<String, Particle> particles;

    private Registries() {
    }

    public static String normalizeKey(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s;
    }

    // ----------------------------------------------------------------- materials

    public static Material material(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material m = Material.matchMaterial(name.trim());
        if (m == null) {
            m = Material.matchMaterial(name.trim().replace(' ', '_').replace('-', '_'));
        }
        if (m != null && isLegacy(m)) {
            return null;
        }
        return m;
    }

    @SuppressWarnings("deprecation")
    public static boolean isLegacy(Material material) {
        return material.isLegacy();
    }

    // ----------------------------------------------------------------- entities

    public static EntityType entityType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, EntityType> map = entityTypes;
        if (map == null) {
            map = new HashMap<>();
            for (EntityType type : EntityType.values()) {
                if (type == EntityType.UNKNOWN) {
                    continue;
                }
                map.put(type.name().toLowerCase(Locale.ROOT), type);
                try {
                    NamespacedKey key = ((Keyed) type).getKey();
                    map.put(key.getKey(), type);
                } catch (RuntimeException ignored) {
                    // some special types have no key
                }
            }
            entityTypes = map;
        }
        String key = normalizeKey(name);
        EntityType type = map.get(key);
        if (type == null && ENTITY_ALIASES.containsKey(key)) {
            type = map.get(ENTITY_ALIASES.get(key));
        }
        return type;
    }

    public static String entityKey(EntityType type) {
        try {
            return ((Keyed) type).getKey().getKey();
        } catch (RuntimeException e) {
            return type.name().toLowerCase(Locale.ROOT);
        }
    }

    // ----------------------------------------------------------------- potion effects

    public static PotionEffectType potionEffect(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = normalizeKey(name);
        key = EFFECT_ALIASES.getOrDefault(key, key);
        NamespacedKey nk = NamespacedKey.fromString(key.contains(":") ? key : "minecraft:" + key);
        if (nk == null) {
            return null;
        }
        return Registry.EFFECT.get(nk);
    }

    public static String key(Keyed keyed) {
        try {
            NamespacedKey k = keyed.getKey();
            return k.getNamespace().equals(NamespacedKey.MINECRAFT) ? k.getKey() : k.toString();
        } catch (RuntimeException e) {
            return String.valueOf(keyed);
        }
    }

    // ----------------------------------------------------------------- enchantments

    public static Enchantment enchantment(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String raw = name.trim().toLowerCase(Locale.ROOT);
        if (raw.contains(":") && !raw.startsWith("minecraft:")) {
            NamespacedKey nk = NamespacedKey.fromString(raw);
            return nk == null ? null : Registry.ENCHANTMENT.get(nk);
        }
        String key = normalizeKey(name);
        key = ENCHANT_ALIASES.getOrDefault(key, key);
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    // ----------------------------------------------------------------- attributes

    /**
     * Resolves an attribute by trying several keys (for example {@code attack_speed} and the pre
     * 1.21.2 key {@code generic.attack_speed}).
     */
    public static Attribute attribute(String... keys) {
        for (String key : keys) {
            try {
                Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (a != null) {
                    return a;
                }
            } catch (RuntimeException ignored) {
                // try next candidate
            }
        }
        return null;
    }

    public static Attribute attackSpeed() {
        return attribute("attack_speed", "generic.attack_speed");
    }

    public static Attribute attackDamage() {
        return attribute("attack_damage", "generic.attack_damage");
    }

    public static Attribute maxHealth() {
        return attribute("max_health", "generic.max_health");
    }

    public static Attribute armor() {
        return attribute("armor", "generic.armor");
    }

    public static Attribute armorToughness() {
        return attribute("armor_toughness", "generic.armor_toughness");
    }

    // ----------------------------------------------------------------- sounds

    /**
     * Resolves a sound name to a namespaced sound key usable with
     * {@code Player#playSound(Location, String, float, float)}.
     * Accepts keys ({@code entity.player.levelup}, {@code minecraft:entity.player.levelup}),
     * enum-style names ({@code ENTITY_PLAYER_LEVELUP}) and custom resource pack keys
     * ({@code myserver:custom.hit}).
     *
     * @return the namespaced key or {@code null} when the vanilla sound does not exist
     */
    public static String sound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains(":") && !lower.startsWith("minecraft:")) {
            return lower; // custom resource pack sound, cannot be validated
        }
        Map<String, String> map = soundNames();
        if (lower.startsWith("minecraft:")) {
            lower = lower.substring("minecraft:".length());
        }
        if (map.containsValue("minecraft:" + lower)) {
            return "minecraft:" + lower;
        }
        String byName = map.get(trimmed.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (byName != null) {
            return byName;
        }
        if (map.isEmpty() && lower.contains(".")) {
            return "minecraft:" + lower; // registry unavailable (unit tests); trust the key
        }
        return null;
    }

    private static Map<String, String> soundNames() {
        Map<String, String> map = soundNames;
        if (map == null) {
            map = new HashMap<>();
            try {
                for (Object sound : Registry.SOUNDS) {
                    NamespacedKey key = ((Keyed) sound).getKey();
                    map.put(key.getKey().toUpperCase(Locale.ROOT).replace('.', '_'), key.toString());
                }
            } catch (Throwable ignored) {
                // registry not available in this environment
            }
            soundNames = map;
        }
        return map;
    }

    // ----------------------------------------------------------------- particles

    public static Particle particle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Particle> map = particles;
        if (map == null) {
            map = new HashMap<>();
            for (Particle p : Particle.values()) {
                map.put(p.name().toLowerCase(Locale.ROOT), p);
                try {
                    map.put(p.getKey().getKey(), p);
                } catch (RuntimeException ignored) {
                    // no key
                }
            }
            particles = map;
        }
        String key = normalizeKey(name);
        Particle p = map.get(key);
        if (p == null && PARTICLE_ALIASES.containsKey(key)) {
            p = map.get(PARTICLE_ALIASES.get(key));
        }
        return p;
    }
}
