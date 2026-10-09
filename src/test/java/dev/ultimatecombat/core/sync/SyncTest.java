package dev.ultimatecombat.core.sync;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.player.PlayerProfile;
import dev.ultimatecombat.modules.bounties.BountyModule;
import dev.ultimatecombat.support.TestEconomy;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 27: cross-server synchronisation. Another server is simulated by writing to the shared
 * database and delivering the message it would publish. The Redis transport itself needs a real
 * Redis server and is not covered here.
 */
class SyncTest extends TestBase {

    @Override
    protected void beforeLoad() {
        TestEconomy.install(server);
    }

    @Test
    void redisIsOffByDefault() {
        assertFalse(plugin.sync().active());
        plugin.sync().publish("profile", UUID.randomUUID().toString()); // no-op without Redis
    }

    @Test
    void bountyPlacedOnAnotherServerAppearsAfterTheMessage() throws Exception {
        BountyModule bounties = plugin.module(BountyModule.class);
        PlayerMock target = join("Target");
        assertEquals(0.0, bounties.total(target.getUniqueId()), 0.001);

        plugin.database().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + plugin.database().t("bounties")
                    + " (data_group, target_uuid, target_name, setter_uuid, setter_name, amount, tax, anonymous, created_at, expires_at, state)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, 0, 'ACTIVE')")) {
                ps.setString(1, plugin.database().group());
                ps.setString(2, target.getUniqueId().toString());
                ps.setString(3, target.getName());
                ps.setString(4, UUID.randomUUID().toString());
                ps.setString(5, "RemotePlayer");
                ps.setDouble(6, 750);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }).get(10, TimeUnit.SECONDS);
        assertEquals(0.0, bounties.total(target.getUniqueId()), 0.001, "not visible before the message");

        plugin.sync().receive("bounty", new String[]{target.getUniqueId().toString()});
        settle();
        assertEquals(750.0, bounties.total(target.getUniqueId()), 0.001, "reloaded after the message");
    }

    @Test
    void profileChangedElsewhereIsReloaded() throws Exception {
        PlayerMock p = join("Traveller");
        UUID id = p.getUniqueId();
        p.disconnect();
        settle();
        Optional<PlayerProfile> cached = plugin.players().lookup("Traveller").get(10, TimeUnit.SECONDS);
        assertTrue(cached.isPresent());
        assertEquals(0, cached.get().stats().kills());

        // the other server wrote 3 kills
        plugin.database().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + plugin.database().t("stats")
                    + " SET kills = 3 WHERE uuid = ? AND data_group = ?")) {
                ps.setString(1, id.toString());
                ps.setString(2, plugin.database().group());
                ps.executeUpdate();
            }
        }).get(10, TimeUnit.SECONDS);
        plugin.sync().receive("profile", new String[]{id.toString()});
        settle();
        Optional<PlayerProfile> fresh = plugin.players().lookup("Traveller").get(10, TimeUnit.SECONDS);
        assertEquals(3, fresh.orElseThrow().stats().kills());
    }

    @Test
    void malformedMessagesAreIgnored() throws Exception {
        plugin.sync().receive("profile", new String[]{"not-a-uuid"});
        plugin.sync().receive("bounty", new String[0]);
        plugin.sync().receive("unknown-type", new String[]{"x"});
        settle();
        assertTrue(plugin.isEnabled());
    }
}
