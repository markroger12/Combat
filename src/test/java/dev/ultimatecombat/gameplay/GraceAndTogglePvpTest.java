package dev.ultimatecombat.gameplay;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.api.event.GraceChangeEvent;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.grace.GraceModule;
import dev.ultimatecombat.modules.togglepvp.TogglePvpModule;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 14 and 15: grace protection (including expiry) and toggle-PvP restrictions.
 */
class GraceAndTogglePvpTest extends TestBase {

    private GraceModule grace() {
        return plugin.module(GraceModule.class);
    }

    private TogglePvpModule toggle() {
        return plugin.module(TogglePvpModule.class);
    }

    @Test
    void newPlayersAreProtected() throws Exception {
        PlayerMock attacker = join("Attacker");
        PlayerMock newbie = join("Newbie");
        assertTrue(grace().hasGrace(newbie), "first join starts grace");
        assertTrue(hit(attacker, newbie, 4).isCancelled(), "attack on a protected player is cancelled");
        assertEquals(20.0, newbie.getHealth(), 0.001);
        assertFalse(plugin.module(CombatModule.class).isTagged(newbie));
    }

    @Test
    void graceExpiresWithPlaytime() throws Exception {
        setAndReload("grace.yml", "first-join.duration-minutes", "3s");
        PlayerMock attacker = join("Attacker");
        PlayerMock newbie = join("Newbie");
        assertTrue(grace().hasGrace(newbie));
        ticks(20 * 4);
        assertFalse(grace().hasGrace(newbie), "grace expired after 3 seconds of playtime");
        grace().end(attacker, GraceChangeEvent.Reason.ADMIN_REMOVE);
        assertFalse(hit(attacker, newbie, 2).isCancelled(), "no longer protected");
    }

    @Test
    void attackingSomeoneEndsOwnGrace() throws Exception {
        PlayerMock protectedAttacker = join("Protected");
        PlayerMock victim = join("Victim");
        grace().end(victim, GraceChangeEvent.Reason.ADMIN_REMOVE);
        assertTrue(grace().hasGrace(protectedAttacker));
        assertFalse(hit(protectedAttacker, victim, 2).isCancelled());
        assertFalse(grace().hasGrace(protectedAttacker), "end-on-attack");
    }

    @Test
    void disabledPvpBlocksDamageBothWays() throws Exception {
        module("grace", false);
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        assertTrue(toggle().set(b, false, true));
        assertFalse(toggle().pvpEnabled(b));
        assertTrue(hit(a, b, 2).isCancelled(), "cannot attack a player with PvP off");
        assertTrue(hit(b, a, 2).isCancelled(), "a player with PvP off cannot attack");
        assertTrue(toggle().set(b, true, true));
        assertFalse(hit(a, b, 2).isCancelled());
    }

    @Test
    void cannotDisablePvpWhileInCombat() throws Exception {
        module("grace", false);
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        hit(a, b, 1);
        assertTrue(plugin.module(CombatModule.class).isTagged(b));
        b.performCommand("ultimatecombat togglepvp off");
        b.performCommand("ultimatecombat togglepvp confirm");
        assertTrue(toggle().pvpEnabled(b), "still enabled while tagged");
    }

    @Test
    void togglePvpCommandWithConfirmation() throws Exception {
        module("grace", false);
        PlayerMock p = join("Player");
        p.performCommand("ultimatecombat togglepvp off");
        assertTrue(toggle().pvpEnabled(p), "needs confirmation first");
        p.performCommand("ultimatecombat togglepvp confirm");
        assertFalse(toggle().pvpEnabled(p));
    }
}
