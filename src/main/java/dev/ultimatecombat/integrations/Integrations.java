package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Declares every supported third-party integration. Hook classes are only loaded (inside the
 * suppliers) when the other plugin is installed and the hook is enabled in integrations.yml.
 */
public final class Integrations {

    private Integrations() {
    }

    public static void defineAll(UltimateCombatPlugin plugin, IntegrationManager manager) {
        manager.define("vault", "Vault", () -> new VaultHook());
        manager.define("placeholderapi", "PlaceholderAPI", () -> new PlaceholderApiHook());
        manager.define("worldguard", "WorldGuard", () -> new WorldGuardHook());
        manager.define("towny", "Towny", () -> new TownyHook());
        manager.define("factions", "Factions", () -> new FactionsHook(upper(reader(plugin), "hooks.factions.ally-relations",
                Set.of("MEMBER", "ALLY"))));
        manager.define("parties", "Parties", () -> new PartiesHook());
        manager.define("superiorskyblock2", "SuperiorSkyblock2", () -> new SuperiorSkyblockHook());
        manager.define("essentials", "Essentials", () -> new EssentialsHook());
        manager.define("libsdisguises", "LibsDisguises", () -> new LibsDisguisesHook());
        manager.define("citizens", "Citizens", () -> new CitizensHook());
        manager.define("mythicmobs", "MythicMobs", () -> new MythicMobsHook(lower(reader(plugin), "hooks.mythicmobs.boss-types")));
        manager.define("mmoitems", "MMOItems", () -> new MmoItemsHook());
        manager.define("advancedenchantments", "AdvancedEnchantments", () -> new AdvancedEnchantmentsHook());
        manager.define("protocollib", "ProtocolLib", () -> new ProtocolLibHook());
    }

    private static ConfigReader reader(UltimateCombatPlugin plugin) {
        return plugin.configs().file("integrations.yml").reader();
    }

    private static Set<String> upper(ConfigReader r, String path, Set<String> def) {
        if (!r.has(path)) {
            return def;
        }
        return r.stringList(path).stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> lower(ConfigReader r, String path) {
        return r.stringList(path).stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
