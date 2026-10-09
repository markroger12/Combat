package dev.ultimatecombat.combat;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.modules.corpses.CorpseModule;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 25 and 26: legacy (1.8) PvP behaviour and corpse inventories without item loss or
 * duplication.
 */
class OldPvpAndCorpseTest extends TestBase {

    @Override
    protected void configure() {
        module("grace", false);
    }

    @Test
    void legacyAttackSpeedAndNoSweepAttacks() throws Exception {
        PlayerMock p = join("Classic");
        Attribute speed = Registries.attribute("attack_speed", "generic.attack_speed");
        assertNotNull(speed);
        p.registerAttribute(speed); // MockBukkit players have no attack speed attribute by default
        p.getAttribute(speed).setBaseValue(4.0);
        module("old-pvp", true);
        ticks(5);
        assertEquals(40.0, p.getAttribute(speed).getBaseValue(), 0.001, "legacy attack speed");

        PlayerMock other = join("Other");
        @SuppressWarnings({"deprecation", "removal"})
        EntityDamageByEntityEvent sweep = new EntityDamageByEntityEvent(p, other, EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).withCausingEntity(p)
                        .withDirectEntity(p).build(), 1);
        server.getPluginManager().callEvent(sweep);
        assertTrue(sweep.isCancelled(), "sweep attacks disabled");

        module("old-pvp", false);
        ticks(5);
        assertEquals(4.0, p.getAttribute(speed).getBaseValue(), 0.001, "modern attack speed restored");
    }

    private int diamonds(Iterable<ItemStack> items) {
        int n = 0;
        for (ItemStack i : items) {
            if (i != null && i.getType() == Material.DIAMOND) {
                n += i.getAmount();
            }
        }
        return n;
    }

    private int diamondsOnGround() {
        int n = 0;
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (item.getItemStack().getType() == Material.DIAMOND) {
                n += item.getItemStack().getAmount();
            }
        }
        return n;
    }

    @Test
    void corpseKeepsItemsExactlyOnceAndCanBeLooted() throws Exception {
        module("corpses", true);
        CorpseModule corpses = plugin.module(CorpseModule.class);
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        victim.getLocation().getChunk().load(); // MockBukkit only loads chunks on request
        victim.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        kill(killer, victim);
        settle();
        assertEquals(5, diamonds(corpses.contents(victim.getUniqueId())), "items stored in the corpse");
        assertEquals(0, diamondsOnGround(), "nothing dropped on the ground as well");

        Interaction hitbox = null;
        for (Entity e : world.getEntities()) {
            if (e instanceof Interaction i) {
                hitbox = i;
            }
        }
        assertNotNull(hitbox, "corpse hitbox spawned");
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(killer, hitbox));
        ItemStack[] contents = killer.getOpenInventory().getTopInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) {
                killer.getInventory().addItem(contents[i]);
                killer.getOpenInventory().getTopInventory().setItem(i, null);
            }
        }
        killer.closeInventory();
        settle();
        assertEquals(5, diamonds(java.util.Arrays.asList(killer.getInventory().getContents())), "looted");
        assertEquals(0, diamonds(corpses.contents(victim.getUniqueId())), "corpse empty and removed");
    }

    @Test
    void expiredCorpseDropsItsItemsOnce() throws Exception {
        module("corpses", true);
        setAndReload("corpses.yml", "duration-seconds", 5); // minimum allowed duration
        CorpseModule corpses = plugin.module(CorpseModule.class);
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        victim.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        kill(killer, victim);
        settle();
        assertEquals(4, diamonds(corpses.contents(victim.getUniqueId())));
        Thread.sleep(5_100);
        ticks(40);
        settle();
        assertEquals(0, diamonds(corpses.contents(victim.getUniqueId())));
        assertEquals(4, diamondsOnGround(), "dropped exactly once on expiry");
        ticks(60);
        assertEquals(4, diamondsOnGround());
    }
}
