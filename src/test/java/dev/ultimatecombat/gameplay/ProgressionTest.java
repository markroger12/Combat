package dev.ultimatecombat.gameplay;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.player.PlayerStats;
import dev.ultimatecombat.modules.leaderboards.LeaderboardModule;
import dev.ultimatecombat.modules.placeholders.PlaceholderModule;
import dev.ultimatecombat.support.TestEconomy;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Points and ranks, anti-abuse, kill money, head trophies, StatTrak, leaderboards,
 * placeholders and kill recaps.
 */
class ProgressionTest extends TestBase {

    private TestEconomy economy;

    @Override
    protected void beforeLoad() {
        economy = TestEconomy.install(server);
    }

    @Override
    protected void configure() {
        module("grace", false);
        // mock players share one address; the IP based checks are tested separately
        set("anti-abuse.yml", "same-ip.enabled", false);
        set("anti-abuse.yml", "alt-accounts.enabled", false);
        set("anti-abuse.yml", "respawn-kill.enabled", false);
        plugin.reload("anti-abuse.yml");
    }

    private PlayerStats stats(PlayerMock p) {
        return plugin.players().profile(p).stats();
    }

    private void revive(PlayerMock p) {
        p.respawn();
        p.setHealth(20);
    }

    @Test
    void killsGivePointsAndRanks() throws Exception {
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        kill(killer, victim);
        settle();
        assertEquals(10, stats(killer).points());
        assertEquals(0, stats(victim).points(), "points never drop below the minimum");
        PlaceholderModule papi = plugin.module(PlaceholderModule.class);
        assertEquals("Bronze", Text.strip(papi.resolve(killer, "rank")));
        assertEquals("10", papi.resolve(killer, "points"));
    }

    @Test
    void repeatedKillsOfTheSameVictimStopGivingPoints() throws Exception {
        PlayerMock killer = join("Farmer");
        PlayerMock victim = join("Alt");
        for (int i = 0; i < 5; i++) {
            kill(killer, victim);
            revive(victim);
        }
        settle();
        assertEquals(5, stats(killer).kills(), "kills are still counted (suppress.stats: false)");
        assertEquals(30, stats(killer).points(), "only the first 3 kills (max-kills) give points");
    }

    @Test
    void killRewardMoneyIsPaid() throws Exception {
        set("economy.yml", "kill-reward.enabled", true);
        plugin.reload("economy.yml");
        PlayerMock killer = join("Killer");
        kill(killer, join("Victim"));
        settle();
        assertEquals(50.0, economy.getBalance(killer), 0.001);
    }

    @Test
    void headTrophyDropsAndSellsOnce() throws Exception {
        module("head-trophies", true);
        set("heads.yml", "drop-chance", 100);
        set("heads.yml", "give-directly-to-killer", true);
        plugin.reload("heads.yml");
        PlayerMock killer = join("Hunter");
        PlayerMock victim = join("Prey");
        economy.set(victim, 1000);
        kill(killer, victim);
        settle();
        assertEquals(950.0, economy.getBalance(victim), 0.001, "5% money penalty");
        ItemStack head = null;
        for (ItemStack item : killer.getInventory().getContents()) {
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                head = item;
            }
        }
        assertTrue(head != null, "head given to the killer");
        killer.getInventory().setItemInMainHand(head.clone());
        killer.performCommand("ultimatecombat sellhead");
        settle();
        assertEquals(50.0, economy.getBalance(killer), 0.001, "sold for the penalty value");
        killer.getInventory().setItemInMainHand(head.clone());
        killer.performCommand("ultimatecombat sellhead");
        settle();
        assertEquals(50.0, economy.getBalance(killer), 0.001, "a copied head cannot be sold twice");
    }

    @Test
    void statTrakCountsKills() throws Exception {
        PlayerMock killer = join("Collector");
        killer.setOp(true);
        killer.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        killer.performCommand("ultimatecombat stattrak register");
        settle();
        kill(killer, join("Victim1"));
        kill(killer, join("Victim2"));
        settle();
        List<String> lore = killer.getInventory().getItemInMainHand().getItemMeta().getLore();
        assertTrue(lore != null && lore.stream().map(Text::strip).anyMatch(l -> l.contains("kills: 2")), "lore shows 2 kills: " + lore);
    }

    @Test
    void leaderboardsAndPlaceholders() throws Exception {
        PlayerMock best = join("Best");
        PlayerMock second = join("Second");
        kill(best, join("V1"));
        kill(best, join("V2"));
        kill(second, join("V3"));
        settle();
        LeaderboardModule boards = plugin.module(LeaderboardModule.class);
        boards.refresh();
        settle();
        List<LeaderboardModule.Entry> top = boards.top(LeaderboardModule.Board.KILLS);
        assertEquals("Best", top.get(0).name());
        assertEquals(2.0, top.get(0).value(), 0.001);
        assertEquals("Second", top.get(1).name());
        PlaceholderModule papi = plugin.module(PlaceholderModule.class);
        assertEquals("Best", papi.resolve(null, "top_kills_1_name"));
        assertEquals("2", papi.resolve(null, "top_kills_1_value"));
        assertEquals("---", papi.resolve(null, "top_kills_99_name"));
        assertEquals("1", papi.resolve(best, "position_kills"));
        assertEquals("2", papi.resolve(best, "kills"));
        hit(best, second, 1);
        assertEquals("true", papi.resolve(best, "in_combat"));
        assertEquals("Second", papi.resolve(best, "combat_opponent"));
        assertEquals(null, papi.resolve(best, "no_such_placeholder"));
    }

    @Test
    void killRecapIsStored() throws Exception {
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        hit(killer, victim, 5);
        kill(killer, victim);
        settle();
        long rows = plugin.database().supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + plugin.database().t("kill_recaps"));
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }).get(10, TimeUnit.SECONDS);
        assertEquals(1, rows);
    }

    @Test
    void sameNetworkKillsAreSuppressed() throws Exception {
        setAndReload("anti-abuse.yml", "same-ip.enabled", true);
        PlayerMock killer = join("Killer", "203.0.113.7");
        PlayerMock victim = join("Victim", "203.0.113.7");
        PlayerMock stranger = join("Stranger", "198.51.100.20");
        String a = plugin.players().profile(killer).ipHash();
        String b = plugin.players().profile(victim).ipHash();
        assertTrue(a != null && a.equals(b), "same address, same hash");
        assertTrue(a.length() == 64 && !a.contains("."), "no raw IP address stored");
        kill(killer, victim);
        settle();
        assertEquals(0, stats(killer).points(), "same network: no points");
        assertEquals(1, stats(killer).kills(), "the kill itself is still counted");
        kill(killer, stranger);
        settle();
        assertEquals(10, stats(killer).points(), "a different network gives points");
    }
}
