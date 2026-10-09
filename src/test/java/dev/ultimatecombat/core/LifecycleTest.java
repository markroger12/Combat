package dev.ultimatecombat.core;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios 23 and 29: scheduler abstraction (Bukkit implementation; Folia needs a real Folia
 * server and is not covered here) and disabling the plugin with players in combat.
 */
class LifecycleTest extends TestBase {

    @Test
    void bukkitSchedulerIsUsedOnNonFoliaServers() {
        assertFalse(plugin.platform().isFolia());
        assertEquals("BukkitTaskScheduler", plugin.scheduler().getClass().getSimpleName());
    }

    @Test
    void schedulerRunsDelaysTimersAndCancels() throws Exception {
        AtomicInteger later = new AtomicInteger();
        AtomicInteger timer = new AtomicInteger();
        plugin.scheduler().runGlobalLater(later::incrementAndGet, 10);
        ScheduledTask repeating = plugin.scheduler().runGlobalTimer(timer::incrementAndGet, 1, 5);
        ticks(9);
        assertEquals(0, later.get());
        ticks(2);
        assertEquals(1, later.get());
        int before = timer.get();
        assertTrue(before >= 2);
        repeating.cancel();
        ticks(20);
        assertEquals(before, timer.get(), "cancelled timer stops");

        Thread main = Thread.currentThread();
        CompletableFuture<Thread> async = new CompletableFuture<>();
        plugin.scheduler().runAsync(() -> async.complete(Thread.currentThread()));
        ticks(1);
        assertNotEquals(main, async.get(5, TimeUnit.SECONDS), "async work runs off the main thread");

        PlayerMock p = join("Entity");
        AtomicInteger entity = new AtomicInteger();
        plugin.scheduler().runEntityLater(p, entity::incrementAndGet, 3);
        ticks(4);
        assertEquals(1, entity.get());
        assertTrue(plugin.scheduler().isOwnedByCurrentThread(p));
    }

    @Test
    void disablingWithPlayersInCombatIsClean() throws Exception {
        module("grace", false);
        PlayerMock a = join("A");
        PlayerMock b = join("B");
        hit(a, b, 2);
        assertTrue(plugin.module(CombatModule.class).isTagged(b));
        double health = b.getHealth();

        server.getPluginManager().disablePlugin(plugin);
        assertFalse(plugin.isEnabled());
        assertEquals(health, b.getHealth(), 0.001, "nobody is punished because of the shutdown");
        assertEquals(0, HandlerList.getRegisteredListeners(plugin).size(), "no listeners left");
        assertEquals(0, server.getScheduler().getPendingTasks().stream().filter(t -> t.getOwner() == plugin).count(),
                "no scheduled tasks left");
        ticks(40); // nothing of the plugin may run any more
    }
}
