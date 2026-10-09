package dev.ultimatecombat.gameplay;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.player.PlayerStats;
import dev.ultimatecombat.modules.bounties.BountyModule;
import dev.ultimatecombat.support.TestEconomy;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 13 and 16: bounty rewards/refunds and killstreak calculations.
 */
class KillsAndBountiesTest extends TestBase {

    private TestEconomy economy;

    @Override
    protected void beforeLoad() {
        economy = TestEconomy.install(server);
    }

    @Override
    protected void configure() {
        module("grace", false);
        // all mock players share one IP address; keep anti-abuse from suppressing these kills
        module("anti-abuse", false);
    }

    private PlayerStats stats(PlayerMock p) {
        return plugin.players().profile(p).stats();
    }

    @Test
    void killstreakCountsAndResets() throws Exception {
        PlayerMock killer = join("Killer");
        for (int i = 0; i < 3; i++) {
            PlayerMock victim = join("Victim" + i);
            kill(killer, victim);
        }
        settle();
        assertEquals(3, stats(killer).kills());
        assertEquals(3, stats(killer).streak());
        assertEquals(3, stats(killer).bestStreak());

        PlayerMock avenger = join("Avenger");
        killer.setHealth(20);
        kill(avenger, killer);
        settle();
        assertEquals(0, stats(killer).streak(), "streak ends on death");
        assertEquals(3, stats(killer).bestStreak(), "best streak kept");
        assertEquals(1, stats(killer).deaths());
        assertEquals(1, stats(avenger).streak());
    }

    @Test
    void bountyIsPaidToTheKillerExactlyOnce() throws Exception {
        BountyModule bounties = plugin.module(BountyModule.class);
        assertTrue(plugin.economy().available(), "Vault economy hooked");
        PlayerMock setter = join("Setter");
        PlayerMock target = join("Target");
        PlayerMock hunter = join("Hunter");
        economy.set(setter, 1000);

        setter.performCommand("ultimatecombat bounty set Target 200");
        settle();
        assertEquals(200.0, bounties.total(target.getUniqueId()), 0.001);
        assertEquals(1000 - 200 - 10, economy.getBalance(setter), 0.001, "amount + 5% tax");

        kill(hunter, target);
        settle();
        assertEquals(200.0, economy.getBalance(hunter), 0.001, "claimed once");
        assertEquals(0.0, bounties.total(target.getUniqueId()), 0.001, "bounty gone after the claim");

        target.setHealth(20);
        kill(hunter, target);
        settle();
        assertEquals(200.0, economy.getBalance(hunter), 0.001, "no second payout");
    }

    @Test
    void removedBountyIsRefundedOnce() throws Exception {
        BountyModule bounties = plugin.module(BountyModule.class);
        PlayerMock setter = join("Setter");
        PlayerMock target = join("Target");
        economy.set(setter, 1000);
        setter.performCommand("ultimatecombat bounty set Target 300");
        settle();
        double afterPlacing = economy.getBalance(setter);
        assertEquals(300.0, bounties.total(target.getUniqueId()), 0.001);

        server.executeConsole("ultimatecombat", "bounty", "remove", "Target");
        settle();
        server.executeConsole("ultimatecombat", "bounty", "remove", "Target");
        settle();
        assertEquals(afterPlacing + 300, economy.getBalance(setter), 0.001, "amount refunded exactly once");
        assertEquals(0.0, bounties.total(target.getUniqueId()), 0.001);
    }

    @Test
    void bountyNeedsEnoughMoney() throws Exception {
        BountyModule bounties = plugin.module(BountyModule.class);
        PlayerMock setter = join("Poor");
        PlayerMock target = join("Target");
        economy.set(setter, 50);
        setter.performCommand("ultimatecombat bounty set Target 200");
        settle();
        assertEquals(0.0, bounties.total(target.getUniqueId()), 0.001);
        assertEquals(50.0, economy.getBalance(setter), 0.001);
    }
}
