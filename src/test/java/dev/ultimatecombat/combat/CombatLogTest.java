package dev.ultimatecombat.combat;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.api.event.CombatLogEvent;
import dev.ultimatecombat.core.player.PlayerStats;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 7-10: logout during combat, rejoin punishments, no item duplication and no duplicate
 * kill rewards on synthetic deaths.
 */
class CombatLogTest extends TestBase {

    @Override
    protected void configure() {
        module("grace", false);
    }

    private PlayerStats stats(PlayerMock p) {
        return plugin.players().profile(p).stats();
    }

    private int diamondsOnGround() {
        int count = 0;
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (item.getItemStack().getType() == Material.DIAMOND) {
                count += item.getItemStack().getAmount();
            }
        }
        return count;
    }

    private int diamondsIn(PlayerMock p) {
        int count = 0;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() == Material.DIAMOND) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private long historyRows(UUID player) throws Exception {
        return plugin.database().supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + plugin.database().t("combat_log_history")
                    + " WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }).get();
    }

    @Test
    void logoutInCombatKillsLoggerAndCreditsOpponent() throws Exception {
        List<CombatLogEvent> events = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void on(CombatLogEvent event) {
                events.add(event);
            }
        }, MockBukkit.createMockPlugin("Observer"));
        PlayerMock attacker = join("Attacker");
        PlayerMock logger = join("Logger");
        hit(attacker, logger, 2);
        assertTrue(plugin.module(CombatModule.class).isTagged(logger));

        logger.disconnect();
        settle();

        assertEquals(1, events.size(), "one CombatLogEvent");
        assertEquals(1, stats(attacker).kills(), "opponent credited once");
        assertEquals(1, historyRows(logger.getUniqueId()), "history row stored");
    }

    @Test
    void logoutOutOfCombatIsNotPunished() throws Exception {
        PlayerMock p = join("Peaceful");
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 3));
        p.disconnect();
        settle();
        assertEquals(0, historyRows(p.getUniqueId()));
        assertEquals(0, diamondsOnGround());
    }

    @Test
    void deathModeDoesNotDuplicateItems() throws Exception {
        PlayerMock attacker = join("Attacker");
        PlayerMock logger = join("Logger");
        logger.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        hit(attacker, logger, 2);
        logger.disconnect();
        settle();
        int total = diamondsOnGround() + diamondsIn(logger);
        assertTrue(total <= 5, "never more than the original 5 diamonds, was " + total);
    }

    @Test
    void dropAndClearDropsExactlyOnceAndClearsInventory() throws Exception {
        setAndReload("combatlog.yml", "profiles.pvp.kill-mode", "drop-and-clear");
        PlayerMock attacker = join("Attacker");
        PlayerMock logger = join("Logger");
        logger.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        hit(attacker, logger, 2);
        logger.disconnect();
        settle();
        assertEquals(5, diamondsOnGround(), "items dropped once");
        assertEquals(0, diamondsIn(logger), "inventory cleared");
        assertEquals(1, stats(attacker).kills(), "synthetic death credited once");

        logger.reconnect();
        settle();
        assertEquals(0, diamondsIn(logger), "nothing restored on rejoin");
        assertEquals(5, diamondsOnGround(), "no second drop");
    }

    @Test
    void syntheticDeathIsNotCountedTwice() throws Exception {
        setAndReload("combatlog.yml", "profiles.pvp.kill-mode", "drop-and-clear");
        PlayerMock attacker = join("Attacker");
        PlayerMock logger = join("Logger");
        hit(attacker, logger, 2);
        logger.disconnect();
        settle();
        assertEquals(1, stats(attacker).kills());

        logger.reconnect();
        settle();
        Zombie zombie = world.spawn(logger.getLocation(), Zombie.class);
        kill(zombie, logger);
        settle();
        assertEquals(1, stats(attacker).kills(), "a later unrelated death must not credit the old opponent again");
        assertEquals(2, stats(logger).deaths(), "combat log death + real death");
    }

    @Test
    void killOnRejoinAppliesPendingPunishmentOnce() throws Exception {
        setAndReload("combatlog.yml", "profiles.pvp.kill-on-rejoin", true);
        PlayerMock attacker = join("Attacker");
        PlayerMock logger = join("Logger");
        hit(attacker, logger, 2);
        double healthAtLogout = logger.getHealth();
        logger.disconnect();
        settle();
        assertEquals(healthAtLogout, logger.getHealth(), 0.001, "not killed at logout");

        logger.reconnect();
        settle();
        ticks(40);
        settle();
        assertTrue(logger.isDead() || logger.getHealth() <= 0, "killed on rejoin");

        logger.respawn();
        logger.disconnect();
        settle();
        logger.reconnect();
        settle();
        ticks(40);
        assertFalse(logger.isDead(), "the punishment is applied only once");
    }
}
