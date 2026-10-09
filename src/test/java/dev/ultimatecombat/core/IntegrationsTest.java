package dev.ultimatecombat.core;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.support.TestEconomy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 22: missing integrations, late-loaded plugins, config switches and the generic
 * protection check.
 */
class IntegrationsTest extends TestBase {

    private IntegrationManager.Status status(String id) {
        return plugin.integrations().states().stream().filter(s -> s.definition().id().equals(id)).findFirst()
                .orElseThrow().status();
    }

    @Test
    void everythingWorksWithoutThirdPartyPlugins() throws Exception {
        for (IntegrationManager.State state : plugin.integrations().states()) {
            assertEquals(IntegrationManager.Status.NOT_INSTALLED, state.status(), state.definition().id());
        }
        assertNull(plugin.integrations().economy());
        assertFalse(plugin.economy().available());
        assertTrue(plugin.integrations().isPvpAllowedAt(world.getSpawnLocation()));
        PlayerMock p = join("Player");
        assertFalse(plugin.integrations().isVanished(p));
        server.executeConsole("ultimatecombat", "integrations");
    }

    @Test
    void pluginInstalledLaterIsHooked() throws Exception {
        TestEconomy.install(server);
        ticks(2);
        assertEquals(IntegrationManager.Status.ACTIVE, status("vault"));
        assertTrue(plugin.economy().available());
    }

    @Test
    void hookCanBeSwitchedOffInIntegrationsYml() throws Exception {
        TestEconomy.install(server);
        ticks(2);
        assertEquals(IntegrationManager.Status.ACTIVE, status("vault"));
        setAndReload("integrations.yml", "hooks.vault.enabled", false);
        assertEquals(IntegrationManager.Status.DISABLED_IN_CONFIG, status("vault"));
        assertFalse(plugin.economy().available());
        setAndReload("integrations.yml", "hooks.vault.enabled", true);
        assertEquals(IntegrationManager.Status.ACTIVE, status("vault"));
    }

    @Test
    void genericProtectionCheckAsksOtherPlugins() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        assertTrue(plugin.integrations().canAttack(a, b));
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.NORMAL)
            public void protect(EntityDamageByEntityEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("ClaimPlugin"));
        assertTrue(plugin.integrations().canAttack(a, b), "off by default");
        setAndReload("integrations.yml", "generic-protection-check.enabled", true);
        assertFalse(plugin.integrations().canAttack(a, b), "claim plugin denies the simulated attack");
        assertFalse(plugin.module(dev.ultimatecombat.modules.combat.CombatModule.class).isTagged(b),
                "the simulated event never tags anyone");
    }
}
