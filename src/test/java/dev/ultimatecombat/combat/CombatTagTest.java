package dev.ultimatecombat.combat;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combat.CombatTag;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 1-6: PvP tagging, multiple attackers, PvE tagging, projectiles, timer expiry and
 * cancelled attacks.
 */
class CombatTagTest extends TestBase {

    @Override
    protected void configure() {
        module("grace", false); // new players would otherwise be protected
    }

    private CombatModule combat() {
        return plugin.module(CombatModule.class);
    }

    @Test
    void pvpHitTagsBothPlayers() throws Exception {
        PlayerMock a = join("Attacker");
        PlayerMock b = join("Victim");
        hit(a, b, 2);
        assertTrue(combat().isTagged(a), "attacker tagged");
        assertTrue(combat().isTagged(b), "victim tagged");
        CombatTag tag = combat().tag(b);
        assertEquals(CombatType.PVP, tag.type());
        assertTrue(tag.opponents().contains(a.getUniqueId()));
        long left = combat().remainingMillis(b);
        assertTrue(left > 15_000 && left <= 20_000, "default duration 20s, was " + left);
    }

    @Test
    void multipleAttackersAreAllRecorded() throws Exception {
        PlayerMock a = join("A");
        PlayerMock c = join("C");
        PlayerMock victim = join("Victim");
        hit(a, victim, 1);
        hit(c, victim, 1);
        CombatTag tag = combat().tag(victim);
        assertNotNull(tag);
        assertEquals(2, tag.opponents().size());
        assertEquals(c.getUniqueId(), tag.lastOpponent());
    }

    @Test
    void pveTaggingOnlyWhenEnabled() throws Exception {
        PlayerMock p = join("Hunter");
        Zombie zombie = world.spawn(p.getLocation().add(1, 0, 0), Zombie.class);
        hit(p, zombie, 1);
        assertFalse(combat().isTagged(p), "PvE is disabled by default");

        setAndReload("combat.yml", "pve.enabled", true);
        hit(p, zombie, 1);
        assertTrue(combat().isTagged(p, CombatType.PVE), "PvE tag after enabling");
    }

    @Test
    void projectileHitTagsShooter() throws Exception {
        PlayerMock shooter = join("Shooter");
        PlayerMock victim = join("Target");
        Arrow arrow = world.spawn(shooter.getLocation(), Arrow.class);
        arrow.setShooter(shooter);
        hit(arrow, victim, 3);
        assertTrue(combat().isTagged(victim));
        assertTrue(combat().isTagged(shooter), "shooter resolved from the projectile");
    }

    @Test
    void tagExpires() throws Exception {
        setAndReload("combat.yml", "pvp.duration-seconds", 1);
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        hit(a, b, 1);
        assertTrue(combat().isTagged(b));
        Thread.sleep(1_200);
        ticks(40);
        assertFalse(combat().isTagged(b), "tag expired");
        assertFalse(combat().isTagged(a), "tag expired");
    }

    @Test
    void cancelledAttackDoesNotTag() throws Exception {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            public void cancel(EntityDamageByEntityEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("Protector"));
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        hit(a, b, 2);
        assertFalse(combat().isTagged(a));
        assertFalse(combat().isTagged(b));
        assertEquals(20.0, b.getHealth(), 0.001, "no damage applied");
    }

    @Test
    void selfDamageDoesNotTag() throws Exception {
        PlayerMock a = join("Lonely");
        hit(a, a, 1);
        assertFalse(combat().isTagged(a));
    }
}
