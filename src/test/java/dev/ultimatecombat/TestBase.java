package dev.ultimatecombat;

import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.player.PlayerProfile;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared set-up for behaviour tests: a mocked server with one world and the plugin enabled.
 */
public abstract class TestBase {

    protected ServerMock server;
    protected WorldMock world;
    protected UltimateCombatPlugin plugin;
    protected final List<LogRecord> severe = new CopyOnWriteArrayList<>();
    private boolean severeAllowed;
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                severe.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void setUpServer() throws Exception {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        beforeLoad();
        Logger.getLogger("UltimateCombat").addHandler(capture);
        plugin = MockBukkit.load(UltimateCombatPlugin.class);
        plugin.getLogger().addHandler(capture);
        assertTrue(plugin.isEnabled(), "plugin enabled");
        assertTrue(plugin.storageReady(), "storage ready");
        configure();
    }

    @AfterEach
    void tearDownServer() {
        try {
            if (!severeAllowed && !severe.isEmpty()) {
                LogRecord first = severe.get(0);
                throw new AssertionError("SEVERE log during the test: " + first.getMessage(), first.getThrown());
            }
        } finally {
            plugin.getLogger().removeHandler(capture);
            Logger.getLogger("UltimateCombat").removeHandler(capture);
            MockBukkit.unmock();
        }
    }

    /**
     * For tests that provoke errors on purpose.
     */
    protected void allowSevereLogs() {
        severeAllowed = true;
    }

    /**
     * Hook for tests that need other plugins (for example a Vault economy) before start-up.
     */
    protected void beforeLoad() throws Exception {
    }

    /**
     * Hook for tests to change settings after the plugin started.
     */
    protected void configure() throws Exception {
    }

    // ------------------------------------------------------------------ configuration

    protected void set(String file, String path, Object value) {
        ConfigFile f = plugin.configs().file(file);
        assertTrue(f.write(path, value), "write " + file + ":" + path);
    }

    protected void setAndReload(String file, String path, Object value) {
        set(file, path, value);
        plugin.reload(file);
    }

    protected void module(String id, boolean enabled) {
        setAndReload("features.yml", "modules." + id, enabled);
        assertTrue(plugin.modules().get(id) != null, "module " + id);
        if (enabled) {
            assertTrue(plugin.modules().get(id).isEnabled(), "module " + id + " enabled");
        } else {
            assertFalse(plugin.modules().get(id).isEnabled(), "module " + id + " disabled");
        }
    }

    // ------------------------------------------------------------------ players and time

    /**
     * Adds a player and waits until the profile has been loaded from the database.
     */
    protected PlayerMock join(String name) throws Exception {
        PlayerMock p = server.addPlayer(name);
        settle();
        PlayerProfile profile = plugin.players().online(p.getUniqueId());
        assertTrue(profile != null && !profile.loadFailed(), "profile of " + name + " loaded");
        return p;
    }

    /**
     * Adds a player connecting from the given IP address.
     */
    protected PlayerMock join(String name, String ip) throws Exception {
        PlayerMock p = new PlayerMock(server, name, java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()));
        p.setAddress(new java.net.InetSocketAddress(java.net.InetAddress.getByName(ip), 25565));
        server.addPlayer(p);
        settle();
        return p;
    }

    /**
     * Waits for queued database work and runs the resulting main-thread callbacks.
     */
    protected void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            plugin.database().supply(c -> null).get(10, TimeUnit.SECONDS);
            server.getScheduler().performTicks(2);
        }
    }

    protected void ticks(long n) {
        server.getScheduler().performTicks(n);
    }

    /**
     * Fires a real damage event (MockBukkit's damage(double, Entity) does not fire events) and,
     * unless a listener cancelled it, applies the final damage like the server would. A lethal
     * hit makes MockBukkit fire the death event with the damager as killer.
     */
    @SuppressWarnings({"deprecation", "removal", "UnstableApiUsage"})
    protected EntityDamageByEntityEvent hit(Entity damager, LivingEntity victim, double damage) {
        Entity causing = damager instanceof Projectile p && p.getShooter() instanceof Entity shooter ? shooter : damager;
        DamageType type = damager instanceof Projectile ? DamageType.ARROW
                : damager instanceof Player ? DamageType.PLAYER_ATTACK : DamageType.MOB_ATTACK;
        DamageSource source = DamageSource.builder(type).withCausingEntity(causing).withDirectEntity(damager).build();
        EntityDamageEvent.DamageCause cause = damager instanceof Projectile ? EntityDamageEvent.DamageCause.PROJECTILE
                : EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(damager, victim, cause, source, damage);
        server.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            victim.setLastDamageCause(event);
            victim.setHealth(Math.max(0, victim.getHealth() - event.getFinalDamage()));
        }
        return event;
    }

    /**
     * Hits until the victim dies.
     */
    protected void kill(Entity damager, LivingEntity victim) {
        hit(damager, victim, 1000);
    }
}
