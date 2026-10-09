package dev.ultimatecombat.gameplay;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.modules.ghosts.GhostModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Death messages, custom tools, damage indicators, deathback, golden apple cooldowns, loot
 * protection, ghost players and the in-game editor.
 */
class FeaturesTest extends TestBase {

    @Override
    protected void configure() {
        module("grace", false);
    }

    private static List<String> drain(PlayerMock p) {
        List<String> out = new ArrayList<>();
        String m;
        while ((m = p.nextMessage()) != null) {
            out.add(Text.strip(m));
        }
        return out;
    }

    @Test
    void deathMessagesAreBroadcastAndCanBeHidden() throws Exception {
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        PlayerMock observer = join("Observer");
        drain(observer);
        kill(killer, victim);
        assertTrue(drain(observer).stream().anyMatch(l -> l.contains("Victim") && l.contains("Killer")), "observer sees the message");

        observer.performCommand("ultimatecombat deathmessages off");
        drain(observer);
        drain(killer);
        victim.respawn();
        victim.setHealth(20);
        kill(killer, victim);
        assertFalse(drain(observer).stream().anyMatch(l -> l.contains("Victim")), "hidden for the observer");
        assertTrue(drain(killer).stream().anyMatch(l -> l.contains("Victim")), "involved players always see it");
    }

    @Test
    void customToolAppliesItsEffects() throws Exception {
        module("custom-tools", true);
        set("custom-tools.yml", "tools.frost-blade.chance", 100);
        set("custom-tools.yml", "tools.frost-blade.cooldown", 0);
        plugin.reload("custom-tools.yml");
        PlayerMock attacker = join("Attacker");
        PlayerMock victim = join("Victim");
        server.executeConsole("ultimatecombat", "customtools", "give", "Attacker", "frost-blade");
        ItemStack blade = null;
        for (ItemStack i : attacker.getInventory().getContents()) {
            if (i != null && i.getType() == Material.DIAMOND_SWORD) {
                blade = i;
            }
        }
        assertNotNull(blade, "tool given");
        attacker.getInventory().setItemInMainHand(blade);
        hit(attacker, victim, 2);
        assertTrue(victim.hasPotionEffect(PotionEffectType.SLOWNESS), "victim slowed");

        PlayerMock other = join("Other");
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        hit(attacker, other, 2);
        assertFalse(other.hasPotionEffect(PotionEffectType.SLOWNESS), "a normal sword does nothing");
    }

    @Test
    void damageIndicatorsAreTemporary() throws Exception {
        module("damage-indicators", true);
        PlayerMock attacker = join("Attacker");
        PlayerMock victim = join("Victim");
        victim.getLocation().getChunk().load();
        hit(attacker, victim, 3);
        assertEquals(1, world.getEntitiesByClass(TextDisplay.class).size(), "number shown");
        ticks(30);
        assertEquals(0, world.getEntitiesByClass(TextDisplay.class).size(), "removed after the duration");
    }

    @Test
    void deathbackReturnsToTheDeathLocation() throws Exception {
        PlayerMock victim = join("Victim");
        PlayerMock killer = join("Killer");
        Location deathSpot = new Location(world, 100, 70, -40);
        victim.teleport(deathSpot);
        kill(killer, victim);
        victim.respawn();
        victim.setHealth(20);
        victim.teleport(new Location(world, 0, 64, 0));
        victim.performCommand("ultimatecombat deathback");
        ticks(2);
        assertEquals(100, victim.getLocation().getBlockX());
        assertEquals(-40, victim.getLocation().getBlockZ());
    }

    @Test
    void goldenAppleCooldown() throws Exception {
        set("apples.yml", "golden-apple.cooldown.seconds", 30);
        plugin.reload("apples.yml");
        PlayerMock p = join("Eater");
        ItemStack apple = new ItemStack(Material.GOLDEN_APPLE);
        PlayerItemConsumeEvent first = new PlayerItemConsumeEvent(p, apple, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(first);
        assertFalse(first.isCancelled());
        PlayerItemConsumeEvent second = new PlayerItemConsumeEvent(p, apple, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(second);
        assertTrue(second.isCancelled(), "second apple within the cooldown");
    }

    @Test
    void lootIsProtectedForTheKiller() throws Exception {
        module("loot-protection", true);
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        PlayerMock thief = join("Thief");
        victim.getInventory().addItem(new ItemStack(Material.DIAMOND, 3));
        kill(killer, victim);
        List<Item> drops = world.getEntitiesByClass(Item.class).stream()
                .filter(i -> i.getItemStack().getType() == Material.DIAMOND).toList();
        assertEquals(1, drops.size(), "dropped once");
        Item drop = drops.get(0);
        EntityPickupItemEvent stolen = new EntityPickupItemEvent(thief, drop, 0);
        server.getPluginManager().callEvent(stolen);
        assertTrue(stolen.isCancelled(), "other players cannot pick it up");
        EntityPickupItemEvent own = new EntityPickupItemEvent(killer, drop, 0);
        server.getPluginManager().callEvent(own);
        assertFalse(own.isCancelled(), "the killer can");
    }

    @Test
    void ghostPlayerKeepsItemsUntilKilledAndNeverDuplicates() throws Exception {
        module("ghost-players", true);
        setAndReload("combatlog.yml", "profiles.pvp.ghost-player", true);
        GhostModule ghosts = plugin.module(GhostModule.class);
        PlayerMock attacker = join("Attacker");
        PlayerMock logger = join("Logger");
        logger.getLocation().getChunk().load();
        logger.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        hit(attacker, logger, 2);
        logger.disconnect();
        settle();

        LivingEntity ghost = null;
        for (Entity e : world.getEntities()) {
            if (e instanceof LivingEntity living && ghosts.isGhost(e)) {
                ghost = living;
            }
        }
        assertNotNull(ghost, "ghost spawned");
        assertEquals(0, countGround(), "nothing dropped while the ghost lives");

        hit(attacker, ghost, 1000);
        settle();
        ticks(5);
        assertEquals(5, countGround(), "the ghost drops the logger's items once");

        logger.reconnect();
        settle();
        ticks(5);
        settle();
        int inInventory = 0;
        for (ItemStack i : logger.getInventory().getContents()) {
            if (i != null && i.getType() == Material.DIAMOND) {
                inInventory += i.getAmount();
            }
        }
        assertEquals(0, inInventory, "the logger lost the items that the ghost dropped");
        assertEquals(5, countGround(), "no duplication");
    }

    private int countGround() {
        int n = 0;
        for (Item i : world.getEntitiesByClass(Item.class)) {
            if (i.getItemStack().getType() == Material.DIAMOND) {
                n += i.getItemStack().getAmount();
            }
        }
        return n;
    }

    @Test
    void editorTogglesASettingThroughValidation() throws Exception {
        PlayerMock admin = join("Admin");
        admin.setOp(true);
        admin.performCommand("ultimatecombat editor combat.yml");
        assertTrue(Text.strip(admin.getOpenInventory().getTitle()).contains("combat.yml"), "editor open");
        admin.simulateInventoryClick(admin.getOpenInventory(), ClickType.LEFT, 10); // "pvp" section
        ticks(2);
        admin.simulateInventoryClick(admin.getOpenInventory(), ClickType.LEFT, 10); // "pvp.enabled"
        ticks(4);
        assertEquals(false, plugin.configs().file("combat.yml").rawValue("pvp.enabled"), "written to combat.yml");
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        hit(a, b, 1);
        assertFalse(plugin.module(dev.ultimatecombat.modules.combat.CombatModule.class).isTagged(b), "applied live");
    }
}
