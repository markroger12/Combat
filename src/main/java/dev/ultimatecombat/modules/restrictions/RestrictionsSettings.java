package dev.ultimatecombat.modules.restrictions;

import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of restrictions.yml.
 */
public record RestrictionsSettings(CommandRules commands, Map<String, RestrictionRule> rules, Options options) {

    /**
     * Rule ids together with whether they are enabled by default and their default "when".
     */
    static final Map<String, RestrictionRule.When> DEFAULT_WHEN = new java.util.LinkedHashMap<>();
    static final Set<String> DEFAULT_ON = Set.of("flight", "elytra", "creative", "god-mode", "vanish", "teleport", "containers",
            "sleeping");

    static {
        for (String id : List.of("flight", "elytra", "riptide", "creative", "god-mode", "vanish", "disguise", "teleport", "containers",
                "block-break", "block-place", "sleeping", "armor-switching", "inventory", "mending", "enchanting", "potions",
                "ender-pearls", "chorus-fruit", "golden-apples", "enchanted-golden-apples", "totems", "shields", "tnt", "wind-charges",
                "custom-items", "entity-interactions")) {
            DEFAULT_WHEN.put(id, RestrictionRule.When.IN_COMBAT);
        }
    }

    public record CommandRules(boolean enabled, boolean whitelist, List<String> list, Set<CombatType> combatTypes, String message,
                               String sound, WorldRules worlds, List<String> blockedAlways) {
    }

    /**
     * A matcher for "custom-items".
     */
    public record ItemMatcher(Material material, String nameContains, int customModelData, String customId) {
    }

    public record Options(boolean flightDisableOnTag, boolean flightRestoreAfterCombat, boolean elytraStopGlidingOnTag,
                          boolean elytraBlockFireworkBoost, boolean creativeForceSurvivalOnTag, boolean creativeBlockGameModeChange,
                          boolean godModeDisableOnTag, boolean vanishUnvanishOnTag, boolean disguiseUndisguiseOnTag,
                          Set<PlayerTeleportEvent.TeleportCause> teleportCauses, double teleportAllowDistance,
                          Set<InventoryType> containerTypes, Set<Material> blockBreakExempt, Set<Material> blockPlaceExempt,
                          boolean inventoryIncludeDropping, Set<InventoryType> enchantingTypes, boolean potionsDrinkable,
                          boolean potionsSplash, boolean potionsLingering, List<ItemMatcher> customItems, Set<EntityType> entityTypes) {
    }

    public static RestrictionsSettings parse(ConfigReader r) {
        ConfigReader c = r.section("commands");
        List<String> list = new ArrayList<>();
        for (String s : c.stringList("list")) {
            list.add(normalize(s));
        }
        List<String> always = new ArrayList<>();
        for (String s : c.stringList("blocked-always")) {
            always.add(normalize(s));
        }
        Set<CombatType> cmdTypes = c.has("combat-types") ? c.enumSet("combat-types", CombatType.class) : EnumSet.of(CombatType.PVP);
        CommandRules commands = new CommandRules(
                c.bool("enabled", true),
                c.choice("mode", "blacklist", "blacklist", "whitelist").equals("whitelist"),
                list, cmdTypes, c.string("message", ""), c.string("sound", "denied"), WorldRules.read(c, "worlds"), always);

        Map<String, RestrictionRule> rules = new HashMap<>();
        for (Map.Entry<String, RestrictionRule.When> e : DEFAULT_WHEN.entrySet()) {
            String id = e.getKey();
            rules.put(id, RestrictionRule.read(id, r.section("rules." + id), DEFAULT_ON.contains(id), e.getValue()));
        }

        ConfigReader o = r.section("rules");
        Set<PlayerTeleportEvent.TeleportCause> causes = o.has("teleport.causes")
                ? o.enumSet("teleport.causes", PlayerTeleportEvent.TeleportCause.class)
                : EnumSet.of(PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.PLUGIN);
        List<ItemMatcher> matchers = new ArrayList<>();
        for (String key : o.keys("custom-items.items")) {
            ConfigReader i = o.section("custom-items.items." + key);
            matchers.add(new ItemMatcher(i.material("material", null), i.string("name-contains", "").toLowerCase(Locale.ROOT),
                    i.integer("custom-model-data", 0, 0, Integer.MAX_VALUE), i.string("custom-id", "")));
        }
        Options options = new Options(
                o.bool("flight.disable-on-tag", true),
                o.bool("flight.restore-after-combat", true),
                o.bool("elytra.stop-gliding-on-tag", true),
                o.bool("elytra.block-firework-boost", true),
                o.bool("creative.force-survival-on-tag", true),
                o.bool("creative.block-gamemode-change", true),
                o.bool("god-mode.disable-on-tag", true),
                o.bool("vanish.unvanish-on-tag", true),
                o.bool("disguise.undisguise-on-tag", true),
                causes,
                o.decimal("teleport.allow-short-distance", 0, 0, 1000),
                o.has("containers.inventory-types") ? o.enumSet("containers.inventory-types", InventoryType.class)
                        : EnumSet.of(InventoryType.ENDER_CHEST),
                o.materialSet("block-break.exempt-materials"),
                o.materialSet("block-place.exempt-materials"),
                o.bool("inventory.include-dropping", false),
                o.has("enchanting.inventory-types") ? o.enumSet("enchanting.inventory-types", InventoryType.class)
                        : EnumSet.of(InventoryType.ENCHANTING, InventoryType.ANVIL),
                o.bool("potions.drinkable", true),
                o.bool("potions.splash", true),
                o.bool("potions.lingering", true),
                matchers,
                o.entityTypes("entity-interactions.entity-types"));
        return new RestrictionsSettings(commands, rules, options);
    }

    /**
     * Lower-cases a command, removes the leading slash and a "plugin:" namespace.
     */
    static String normalize(String command) {
        String s = command.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        int space = s.indexOf(' ');
        String label = space < 0 ? s : s.substring(0, space);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        return s.replaceAll("\\s+", " ");
    }
}
