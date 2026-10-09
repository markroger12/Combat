package dev.ultimatecombat.core.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Scheduler abstraction that maps every operation to the correct thread on Bukkit/Paper
 * (single main thread) and on Folia (global region, owning region, owning entity, async).
 *
 * <p>Rules used throughout the plugin:</p>
 * <ul>
 *     <li>Anything touching an entity (inventory, health, teleport, glowing) runs through
 *     {@link #runEntity(Entity, Runnable)} or {@link #executeEntity(Entity, Runnable)}.</li>
 *     <li>Anything touching blocks/chunks at a location runs through {@link #runAt(Location, Runnable)}.</li>
 *     <li>World-independent state (timers, broadcasts) runs on the global scheduler.</li>
 *     <li>Database and network I/O never runs here; it uses the database executor.</li>
 * </ul>
 * All tasks are tracked and cancelled by {@link #cancelAll()} when the plugin disables.
 */
public interface TaskScheduler {

    ScheduledTask runGlobal(Runnable task);

    ScheduledTask runGlobalLater(Runnable task, long delayTicks);

    ScheduledTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    ScheduledTask runAt(Location location, Runnable task);

    ScheduledTask runAtLater(Location location, Runnable task, long delayTicks);

    /**
     * Schedules a task on the thread that owns the entity. If the entity is removed before the
     * task runs, the task is silently dropped.
     */
    ScheduledTask runEntity(Entity entity, Runnable task);

    ScheduledTask runEntityLater(Entity entity, Runnable task, long delayTicks);

    ScheduledTask runEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks);

    /**
     * Runs immediately when the current thread already owns the entity, otherwise schedules.
     */
    void executeEntity(Entity entity, Runnable task);

    /**
     * Runs immediately when the current thread owns the location, otherwise schedules.
     */
    void executeAt(Location location, Runnable task);

    /**
     * Runs immediately on the global/main thread when already there, otherwise schedules.
     */
    void executeGlobal(Runnable task);

    ScheduledTask runAsync(Runnable task);

    ScheduledTask runAsyncLater(Runnable task, long delayTicks);

    ScheduledTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks);

    boolean isOwnedByCurrentThread(Entity entity);

    boolean isOwnedByCurrentThread(Location location);

    boolean isGlobalThread();

    /**
     * Teleports an entity using the platform's safe path (async teleport on Paper/Folia).
     */
    void teleport(Entity entity, Location location);

    void cancelAll();

    Platform platform();
}
