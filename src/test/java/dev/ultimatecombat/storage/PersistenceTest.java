package dev.ultimatecombat.storage;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.player.PlayerProfile;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 17, 18, 19 and 28: statistics persistence, database restarts, offline lookups and
 * logout during asynchronous writes.
 */
class PersistenceTest extends TestBase {

    @Override
    protected void configure() {
        module("grace", false);
        module("anti-abuse", false);
    }

    private int storedKills(UUID uuid) throws Exception {
        return plugin.database().supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kills FROM " + plugin.database().t("stats")
                    + " WHERE uuid = ? AND data_group = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, plugin.database().group());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void statisticsAreWrittenOnQuit() throws Exception {
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        kill(killer, victim);
        killer.disconnect();
        settle();
        assertEquals(1, storedKills(killer.getUniqueId()));
    }

    @Test
    void offlineLookupReturnsStoredStatistics() throws Exception {
        PlayerMock killer = join("Killer");
        PlayerMock victim = join("Victim");
        kill(killer, victim);
        kill(killer, join("Second"));
        killer.disconnect();
        settle();
        Optional<PlayerProfile> found = plugin.players().lookup("Killer").get(10, TimeUnit.SECONDS);
        assertTrue(found.isPresent());
        assertEquals(2, found.get().stats().kills());
        Optional<PlayerProfile> unknown = plugin.players().lookup("NeverJoined").get(10, TimeUnit.SECONDS);
        assertTrue(unknown.isEmpty());
    }

    @Test
    void dataSurvivesAPluginRestart() throws Exception {
        PlayerMock killer = join("Killer");
        kill(killer, join("Victim"));
        UUID id = killer.getUniqueId();

        server.getPluginManager().disablePlugin(plugin);
        assertTrue(!plugin.isEnabled());
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.isEnabled() && plugin.storageReady(), "restarted with storage");
        settle();

        killer.disconnect();
        settle();
        killer.reconnect();
        settle();
        PlayerProfile profile = plugin.players().online(id);
        assertTrue(profile != null && !profile.loadFailed());
        assertEquals(1, profile.stats().kills(), "kill survived the restart");
    }

    @Test
    void quickRejoinDuringSaveLosesNothing() throws Exception {
        PlayerMock killer = join("Killer");
        kill(killer, join("Victim1"));
        // quit and come back before the asynchronous save has finished
        killer.disconnect();
        killer.reconnect();
        kill(killer, join("Victim2"));
        settle();
        assertEquals(2, plugin.players().profile(killer).stats().kills());
        killer.disconnect();
        settle();
        assertEquals(2, storedKills(killer.getUniqueId()), "both kills stored exactly once");
    }
}
