package dev.ultimatecombat.config;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Do not create YAML options without implementing their behaviour": every documented default
 * option must be read by the code when all modules are enabled.
 */
class ConfigCoverageTest {

    /**
     * Options that are read outside the start-up/reload path, with the reason.
     */
    private static final List<String> READ_ELSEWHERE = List.of(
            // read when /uc editor opens (AdminMenuModule.options())
            "menus.yml|editor.",
            // read only when the respective third-party plugin is installed (hook supplier)
            "integrations.yml|hooks.factions.ally-relations",
            "integrations.yml|hooks.mythicmobs.boss-types",
            // per-world overlays are applied as sections on top of each module file
            "worlds.yml|overrides.");

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        ConfigReader.recordAccess(false);
        MockBukkit.unmock();
    }

    @Test
    void everyDefaultOptionIsRead() throws Exception {
        ConfigReader.recordAccess(true);
        UltimateCombatPlugin plugin = MockBukkit.load(UltimateCombatPlugin.class);
        ConfigFile features = plugin.configs().file("features.yml");
        for (Module<?> module : plugin.modules().all()) {
            features.write("modules." + module.id(), true);
        }
        plugin.reload("all");
        Set<String> accessed = ConfigReader.accessed();

        Set<String> unread = new TreeSet<>();
        for (ConfigFile file : plugin.configs().files()) {
            if (file.name().startsWith("languages/")) {
                continue;
            }
            YamlConfiguration defaults = bundled(file.name());
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || key.equals("config-version")) {
                    continue;
                }
                String id = file.name() + "|" + key;
                if (accessed.contains(id) || READ_ELSEWHERE.stream().anyMatch(id::startsWith)) {
                    continue;
                }
                unread.add(id);
            }
        }
        assertTrue(unread.isEmpty(), "Options documented in the default files but never read:\n" + String.join("\n", unread));
    }

    private static YamlConfiguration bundled(String name) throws Exception {
        try (InputStream in = ConfigCoverageTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "bundled resource " + name);
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return y;
        }
    }
}
