package dev.ultimatecombat.config;

import dev.ultimatecombat.modules.leaderboards.LeaderboardModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consistency checks between source code, language files, plugin.yml and the default YAML files.
 */
class StaticChecksTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Pattern KEY = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"");
    private static final Pattern PERMISSION = Pattern.compile("\"(ultimatecombat\\.[a-z0-9.*-]+)\"");
    private static final Pattern BYPASS = Pattern.compile("bypasses\\([a-zA-Z]+, \"([a-z.-]+)\"\\)");
    private static final Pattern STANDALONE = Pattern.compile("standalone\\(\"([a-z-]+)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    /**
     * Literals that look like message keys but are configuration paths or debug counter names.
     */
    private static final Set<String> NOT_MESSAGES = Set.of("combat.tags", "combatlog.punishments", "cooldowns.only-in-combat",
            "cooldowns.drinkable-seconds", "cooldowns.splash-seconds", "cooldowns.lingering-seconds", "potions.drinkable",
            "potions.splash", "potions.lingering", "pvp.duration-seconds", "pvp.time");

    private static String allSources;
    private static YamlConfiguration english;
    private static YamlConfiguration german;
    private static YamlConfiguration pluginYml;

    @BeforeAll
    static void load() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        allSources = sb.toString();
        english = yaml(RESOURCES.resolve("languages/en_US.yml"));
        german = yaml(RESOURCES.resolve("languages/de_DE.yml"));
        String raw = Files.readString(RESOURCES.resolve("plugin.yml"), StandardCharsets.UTF_8).replace("${libraries}", "  - x");
        pluginYml = new YamlConfiguration();
        pluginYml.loadFromString(raw);
    }

    private static YamlConfiguration yaml(Path path) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        return y;
    }

    private static Map<String, String> leaves(ConfigurationSection section) {
        Map<String, String> out = new HashMap<>();
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                out.put(key, String.valueOf(section.get(key)));
            }
        }
        return out;
    }

    @Test
    void everyDefaultFileParsesAndIsDocumented() throws IOException {
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (p.endsWith("plugin.yml")) {
                    continue;
                }
                YamlConfiguration y = new YamlConfiguration();
                try {
                    y.loadFromString(text);
                } catch (Exception e) {
                    throw new AssertionError(p + " does not parse", e);
                }
                assertTrue(text.startsWith("# ====="), p + " starts with a documentation header");
                if (!p.toString().contains("languages")) {
                    assertEquals(1, y.getInt("config-version"), p + " has config-version");
                }
            }
        }
    }

    @Test
    void everyMessageKeyUsedInCodeExists() {
        Set<String> tops = english.getKeys(false);
        Set<String> missing = new TreeSet<>();
        Matcher m = KEY.matcher(allSources);
        while (m.find()) {
            String key = m.group(1);
            if (!tops.contains(key.split("\\.")[0]) || key.endsWith(".yml") || NOT_MESSAGES.contains(key)) {
                continue;
            }
            if (!english.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "message keys missing in en_US.yml: " + missing);
    }

    @Test
    void dynamicMessageFamiliesAreComplete() throws Exception {
        YamlConfiguration restrictions = yaml(RESOURCES.resolve("restrictions.yml"));
        for (String rule : restrictions.getConfigurationSection("rules").getKeys(false)) {
            assertTrue(english.contains("restrictions.blocked." + rule), "restrictions.blocked." + rule);
            assertTrue(english.contains("restrictions.names." + rule), "restrictions.names." + rule);
        }
        for (LeaderboardModule.Board board : LeaderboardModule.Board.values()) {
            assertTrue(english.contains("leaderboard.boards." + board.id()), "leaderboard.boards." + board.id());
        }
        for (String reason : List.of("repeated-kills", "same-ip", "alt-account", "respawn-kill")) {
            assertTrue(english.contains("anti-abuse.suppressed." + reason), reason);
        }
        for (String potion : List.of("drink", "splash", "lingering")) {
            assertTrue(english.contains("potions.names." + potion), potion);
        }
        for (String apple : List.of("golden", "enchanted")) {
            assertTrue(english.contains("apples.names." + apple), apple);
        }
        for (String fallback : List.of("player-other", "mob", "default")) {
            assertTrue(english.isList("death-messages.messages." + fallback), "death message fallback " + fallback);
        }
    }

    @Test
    void germanTranslationIsCompleteWithTheSamePlaceholders() {
        Map<String, String> en = leaves(english);
        Map<String, String> de = leaves(german);
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(de.keySet()), "same keys");
        for (Map.Entry<String, String> e : en.entrySet()) {
            assertEquals(placeholders(e.getValue()), placeholders(de.get(e.getKey())), "placeholders of " + e.getKey());
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void everyPermissionUsedInCodeIsDeclared() throws Exception {
        ConfigurationSection perms = pluginYml.getConfigurationSection("permissions");
        Set<String> missing = new TreeSet<>();
        Matcher m = PERMISSION.matcher(allSources);
        while (m.find()) {
            String perm = m.group(1);
            // Bukkit's YAML loader nests dotted keys, so "a.b" is the section path a -> b
            if (!perm.endsWith(".") && !perms.isConfigurationSection(perm)) {
                missing.add(perm);
            }
        }
        Matcher b = BYPASS.matcher(allSources);
        while (b.find()) {
            String perm = "ultimatecombat.bypass." + b.group(1);
            if (!perms.isConfigurationSection(perm)) {
                missing.add(perm);
            }
        }
        YamlConfiguration restrictions = yaml(RESOURCES.resolve("restrictions.yml"));
        for (String rule : restrictions.getConfigurationSection("rules").getKeys(false)) {
            String perm = "ultimatecombat.bypass.restriction." + rule;
            if (!perms.isConfigurationSection(perm)) {
                missing.add(perm);
            }
        }
        assertTrue(missing.isEmpty(), "permissions missing in plugin.yml: " + missing);
        int bypasses = 0;
        for (String key : perms.getKeys(true)) {
            if (key.startsWith("ultimatecombat.bypass") && key.endsWith(".default")) {
                bypasses++;
                assertEquals("false", perms.getString(key), key + " must be false");
            }
        }
        assertTrue(bypasses > 40, "bypass permissions found: " + bypasses);
    }

    @Test
    void everyStandaloneCommandIsConfigurable() throws Exception {
        YamlConfiguration commands = yaml(RESOURCES.resolve("commands.yml"));
        Matcher m = STANDALONE.matcher(allSources);
        int count = 0;
        while (m.find()) {
            count++;
            assertTrue(commands.isConfigurationSection("commands." + m.group(1)), "commands.yml entry for " + m.group(1));
        }
        assertTrue(count >= 15);
    }
}
