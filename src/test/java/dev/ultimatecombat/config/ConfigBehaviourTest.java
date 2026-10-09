package dev.ultimatecombat.config;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ValidationIssue;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 20 and 21: invalid YAML handling and live configuration reloads.
 */
class ConfigBehaviourTest extends TestBase {

    @Override
    protected void configure() {
        module("grace", false);
    }

    private long tagSeconds(PlayerMock a, PlayerMock b) {
        CombatModule combat = plugin.module(CombatModule.class);
        combat.untag(a, dev.ultimatecombat.api.UntagReason.COMMAND, false);
        combat.untag(b, dev.ultimatecombat.api.UntagReason.COMMAND, false);
        hit(a, b, 0.5);
        return Math.round(combat.remainingMillis(b) / 1000.0);
    }

    @Test
    void defaultsProduceNoWarnings() {
        for (ConfigFile file : plugin.configs().files()) {
            for (ValidationIssue issue : file.issues()) {
                assertFalse(issue.severity() == ValidationIssue.Severity.WARNING, "default file has a warning: " + issue.describe());
            }
        }
    }

    @Test
    void invalidValueFallsBackToDefaultAndIsReported() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        setAndReload("combat.yml", "pvp.duration-seconds", "twenty");
        assertTrue(plugin.configs().file("combat.yml").issues().stream()
                .anyMatch(i -> i.path().equals("pvp.duration-seconds")), "issue reported");
        assertEquals(20, tagSeconds(a, b), "default used");
    }

    @Test
    void yamlSyntaxErrorKeepsTheLastValidConfiguration() throws Exception {
        allowSevereLogs(); // the syntax error is reported as SEVERE on purpose
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        setAndReload("combat.yml", "pvp.duration-seconds", 7);
        assertEquals(7, tagSeconds(a, b));

        ConfigFile file = plugin.configs().file("combat.yml");
        Files.writeString(file.file().toPath(), "pvp:\n  duration-seconds: [broken\n", StandardCharsets.UTF_8);
        plugin.reload("combat.yml");
        assertTrue(file.issues().stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR
                && i.problem().startsWith("YAML syntax error")), "syntax error reported");
        assertFalse(file.parseFailed(), "the last valid version is kept on reload");
        assertEquals(7, tagSeconds(a, b), "previous valid settings still active");
        assertTrue(Files.readString(file.file().toPath()).contains("[broken"), "the administrator's file is not modified");
        assertTrue(plugin.isEnabled());
    }

    @Test
    void configSetCommandAppliesLiveAndRejectsInvalidValues() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        server.executeConsole("ultimatecombat", "config", "set", "combat.yml:pvp.duration-seconds", "9");
        assertEquals(9, tagSeconds(a, b), "applied without restart");

        server.executeConsole("ultimatecombat", "config", "set", "combat.yml:pvp.duration-seconds", "-5");
        assertEquals(9, tagSeconds(a, b), "rejected value rolled back");
        assertEquals(9, ((Number) plugin.configs().file("combat.yml").rawValue("pvp.duration-seconds")).intValue());
    }

    @Test
    void perWorldOverridesApply() throws Exception {
        WorldMock arena = server.addSimpleWorld("arena");
        set("worlds.yml", "overrides.arena.combat.pvp.duration-seconds", 4);
        plugin.reload("all");
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        assertEquals(20, tagSeconds(a, b), "global value in the main world");
        CombatModule combat = plugin.module(CombatModule.class);
        combat.untag(a, dev.ultimatecombat.api.UntagReason.COMMAND, false);
        combat.untag(b, dev.ultimatecombat.api.UntagReason.COMMAND, false); // teleporting in combat is restricted
        a.teleport(new Location(arena, 0, 64, 0));
        b.teleport(new Location(arena, 1, 64, 0));
        assertEquals(4, tagSeconds(a, b), "override in arena");
    }

    @Test
    void disablingAModuleAtRuntimeStopsIt() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        module("combat-tag", false);
        hit(a, b, 1);
        assertFalse(plugin.module(CombatModule.class).isTagged(b));
        module("combat-tag", true);
        hit(a, b, 1);
        assertTrue(plugin.module(CombatModule.class).isTagged(b));
    }
}
