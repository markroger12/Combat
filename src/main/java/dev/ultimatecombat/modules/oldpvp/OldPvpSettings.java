package dev.ultimatecombat.modules.oldpvp;

import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of old-pvp.yml.
 */
public record OldPvpSettings(Mode mode, WorldRules worlds, boolean playerToggle, boolean toggleDefault, double attackSpeed,
                             double modernAttackSpeed, boolean damageEnabled, Map<Material, Double> weaponDamage,
                             double criticalMultiplier, boolean legacySharpness, boolean legacyStrength, boolean regenEnabled,
                             long regenIntervalMillis, double regenAmount, float regenExhaustion, int regenMinFood,
                             boolean legacyArmor, double weaponDurabilityMultiplier, double armorDurabilityMultiplier,
                             double fireTickDamage, double burnDurationMultiplier, boolean disableOffhand, boolean moveOffhandItems,
                             boolean disableShields, boolean blockShieldCrafting, boolean disableSweep, boolean disableSounds,
                             Set<String> blockedSounds) {

    public enum Mode { ALWAYS, IN_COMBAT, NEVER }

    public static OldPvpSettings parse(ConfigReader r) {
        ConfigReader d = r.section("damage");
        Map<Material, Double> weapons = new EnumMap<>(Material.class);
        for (String key : d.keys("weapons")) {
            Material m = Registries.material(key);
            if (m == null) {
                d.warn("weapons." + key, key, "Unknown material (entry ignored)", "a material such as DIAMOND_SWORD", "entry skipped");
                continue;
            }
            weapons.put(m, d.decimal("weapons." + key, 1.0, 0, 2048));
        }
        ConfigReader regen = r.section("regeneration");
        ConfigReader sounds = r.section("sounds");
        Set<String> blocked = new LinkedHashSet<>();
        for (String s : sounds.stringList("blocked")) {
            String key = Registries.sound(s);
            if (key == null) {
                sounds.warn("blocked", s, "Unknown sound (entry ignored)", "a sound key such as entity.player.attack.sweep", "entry skipped");
            } else {
                blocked.add(key);
            }
        }
        return new OldPvpSettings(
                r.enumValue("mode", Mode.class, Mode.ALWAYS),
                WorldRules.read(r, "worlds"),
                r.bool("player-toggle.enabled", false),
                r.bool("player-toggle.default", true),
                r.decimal("attack-speed.legacy", 40.0, 0.1, 1024),
                r.decimal("attack-speed.modern", 4.0, 0.1, 1024),
                d.bool("enabled", true),
                weapons,
                d.decimal("critical-multiplier", 1.5, 1.0, 10),
                d.bool("legacy-sharpness", true),
                d.bool("legacy-strength", false),
                regen.bool("enabled", true),
                regen.durationMillis("interval-seconds", 4000, ConfigReader.TimeUnitHint.SECONDS, 50, 600_000),
                regen.decimal("amount", 1.0, 0, 40),
                (float) regen.decimal("exhaustion", 3.0, 0, 40),
                regen.integer("minimum-food-level", 18, 0, 20),
                r.bool("armor.legacy-reduction", true),
                r.decimal("durability.weapon-multiplier", 1.0, 0, 100),
                r.decimal("durability.armor-multiplier", 1.0, 0, 100),
                r.decimal("fire.fire-tick-damage", 1.0, 0, 100),
                r.decimal("fire.burn-duration-multiplier", 1.0, 0, 100),
                r.bool("offhand.disable", true),
                r.bool("offhand.move-items-to-inventory", true),
                r.bool("shields.disable", true),
                r.bool("shields.block-crafting", true),
                r.bool("disable-sweep-attacks", true),
                sounds.bool("enabled", true),
                blocked);
    }
}
