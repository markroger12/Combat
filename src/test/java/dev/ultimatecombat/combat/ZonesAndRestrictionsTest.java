package dev.ultimatecombat.combat;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 11, 12 and 24: protected (no-PvP) zones, combat border escape attempts and item /
 * command restrictions.
 */
class ZonesAndRestrictionsTest extends TestBase {

    @Override
    protected void configure() {
        module("grace", false);
        set("borders.yml", "zones.spawn", Map.of("world", "world", "min", "-10,0,-10", "max", "10,320,10", "block-pvp", true));
        plugin.reload("borders.yml");
    }

    private Location at(double x) {
        return new Location(world, x, 64, 0);
    }

    private CombatModule combat() {
        return plugin.module(CombatModule.class);
    }

    @Test
    void noPvpInsideProtectedZone() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        a.teleport(at(0));
        b.teleport(at(1));
        assertTrue(hit(a, b, 2).isCancelled(), "PvP blocked inside the zone");
        assertFalse(combat().isTagged(b));
        a.teleport(at(30));
        b.teleport(at(31));
        assertFalse(hit(a, b, 2).isCancelled(), "PvP allowed outside");
    }

    @Test
    void taggedPlayersCannotWalkIntoTheZone() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        a.teleport(at(20));
        b.teleport(at(11.5));
        hit(a, b, 1);
        assertTrue(combat().isTagged(b));
        PlayerMoveEvent move = b.simulatePlayerMove(at(9.5));
        // MockBukkit applies the original target instead of event.getTo(), so check the event itself
        assertTrue(move.isCancelled() || move.getTo().getBlockX() > 10, "moved back out of the zone");
    }

    @Test
    void untaggedPlayersMayEnterTheZone() throws Exception {
        PlayerMock b = join("B");
        b.teleport(at(11.5));
        PlayerMoveEvent move = b.simulatePlayerMove(at(9.5));
        assertFalse(move.isCancelled());
        assertEquals(9, b.getLocation().getBlockX());
    }

    @Test
    void taggedPlayersCannotPearlIntoTheZone() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        a.teleport(at(20));
        b.teleport(at(15));
        hit(a, b, 1);
        assertFalse(b.teleport(at(0), PlayerTeleportEvent.TeleportCause.ENDER_PEARL), "pearl into the zone cancelled");
        assertEquals(15, b.getLocation().getBlockX());
    }

    @Test
    void blockedCommandsInCombat() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        a.teleport(at(30));
        b.teleport(at(31));
        PlayerCommandPreprocessEvent before = new PlayerCommandPreprocessEvent(b, "/spawn");
        server.getPluginManager().callEvent(before);
        assertFalse(before.isCancelled(), "allowed out of combat");
        hit(a, b, 1);
        PlayerCommandPreprocessEvent during = new PlayerCommandPreprocessEvent(b, "/spawn");
        server.getPluginManager().callEvent(during);
        assertTrue(during.isCancelled(), "/spawn is blacklisted in combat");
        PlayerCommandPreprocessEvent other = new PlayerCommandPreprocessEvent(b, "/msg A hi");
        server.getPluginManager().callEvent(other);
        assertFalse(other.isCancelled(), "other commands still work");
    }

    @Test
    void enderPearlsAndCustomItemsCanBeRestricted() throws Exception {
        set("restrictions.yml", "rules.ender-pearls.enabled", true);
        set("restrictions.yml", "rules.custom-items.enabled", true);
        set("restrictions.yml", "rules.custom-items.items.grapple", Map.of("material", "FISHING_ROD", "name-contains", "grappling"));
        plugin.reload("restrictions.yml");
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        a.teleport(at(30));
        b.teleport(at(31));
        ItemStack pearl = new ItemStack(Material.ENDER_PEARL);
        ItemStack grapple = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = grapple.getItemMeta();
        meta.setDisplayName("Grappling Hook");
        grapple.setItemMeta(meta);
        ItemStack rod = new ItemStack(Material.FISHING_ROD);

        assertFalse(use(b, pearl), "pearls allowed out of combat");
        hit(a, b, 1);
        assertTrue(use(b, pearl), "pearls blocked in combat");
        assertTrue(use(b, grapple), "named grappling hook blocked");
        assertFalse(use(b, rod), "a normal fishing rod is not affected");
    }

    @Test
    void flightIsDisabledWhenTagged() throws Exception {
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        a.teleport(at(30));
        b.teleport(at(31));
        b.setAllowFlight(true);
        b.setFlying(true);
        hit(a, b, 1);
        ticks(1);
        assertFalse(b.isFlying());
        assertFalse(b.getAllowFlight());
    }

    private boolean use(PlayerMock player, ItemStack item) {
        player.getInventory().setItemInMainHand(item);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF);
        server.getPluginManager().callEvent(event);
        return event.useItemInHand() == org.bukkit.event.Event.Result.DENY;
    }
}
