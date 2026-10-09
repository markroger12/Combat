package dev.ultimatecombat.modules.ghosts;

import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.entity.EntityType;

import java.util.Set;

/**
 * Immutable snapshot of ghosts.yml.
 */
public record GhostSettings(String npcType, EntityType fallbackType, long durationMillis, boolean usePlayerHealth, double fixedHealth,
                            boolean killable, boolean fightBack, boolean showArmor, boolean showHeldItem, boolean glowing,
                            boolean ai, boolean dropInventory, boolean dropExperience, boolean killOwnerOnRejoin, boolean countAsDeath,
                            boolean creditKiller, boolean removeOnRejoin, boolean broadcastKill, WorldRules worlds,
                            Set<String> excludedRegions) {

    public static GhostSettings parse(ConfigReader r) {
        String type = r.string("npc-type", "auto").trim().toLowerCase(java.util.Locale.ROOT);
        EntityType fallback = EntityType.VILLAGER;
        if (!type.equals("auto") && !type.equals("citizens")) {
            EntityType parsed = Registries.entityType(type);
            if (parsed == null || !parsed.isAlive() || !parsed.isSpawnable()) {
                r.warn("npc-type", type, "Not a spawnable living entity", "auto, citizens, mannequin, villager, zombie ...", "auto");
                type = "auto";
            } else {
                fallback = parsed;
            }
        }
        ConfigReader death = r.section("on-death");
        return new GhostSettings(type, fallback,
                r.durationMillis("duration-seconds", 30_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 3_600_000),
                r.choice("health", "player", "player", "fixed").equals("player"),
                r.decimal("fixed-health", 20, 1, 2048),
                r.bool("killable", true),
                r.bool("fight-back", false),
                r.bool("show-armor", true),
                r.bool("show-held-item", true),
                r.bool("glowing", true),
                r.bool("ai", false),
                death.bool("drop-inventory", true),
                death.bool("drop-experience", true),
                death.bool("kill-owner-on-rejoin", true),
                death.bool("count-as-death", true),
                death.bool("credit-killer", true),
                r.bool("remove-on-rejoin", true),
                death.bool("broadcast", true),
                WorldRules.read(r, "worlds"),
                r.lowerCaseSet("excluded-regions"));
    }
}
