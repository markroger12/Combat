package dev.ultimatecombat.core.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler for Folia's region-threaded server. This class is only loaded when Folia is
 * detected, so it may freely reference the regionised scheduler API.
 */
public final class FoliaTaskScheduler implements TaskScheduler {

    private final Plugin plugin;
    private final Set<FoliaScheduled> tasks = ConcurrentHashMap.newKeySet();

    public FoliaTaskScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    private final class FoliaScheduled implements ScheduledTask {
        private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask handle;
        private volatile boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
            var h = handle;
            if (h != null) {
                h.cancel();
            }
            tasks.remove(this);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private FoliaScheduled track() {
        FoliaScheduled scheduled = new FoliaScheduled();
        tasks.add(scheduled);
        return scheduled;
    }

    private java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> body(
            FoliaScheduled scheduled, Runnable task, boolean repeating) {
        return handle -> {
            if (scheduled.cancelled) {
                handle.cancel();
                return;
            }
            try {
                task.run();
            } finally {
                if (!repeating) {
                    tasks.remove(scheduled);
                }
            }
        };
    }

    private static long atLeastOne(long ticks) {
        return Math.max(1L, ticks);
    }

    @Override
    public ScheduledTask runGlobal(Runnable task) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getGlobalRegionScheduler().run(plugin, body(s, task, false));
        return s;
    }

    @Override
    public ScheduledTask runGlobalLater(Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, body(s, task, false), atLeastOne(delayTicks));
        return s;
    }

    @Override
    public ScheduledTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, body(s, task, true),
                atLeastOne(delayTicks), atLeastOne(periodTicks));
        return s;
    }

    @Override
    public ScheduledTask runAt(Location location, Runnable task) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getRegionScheduler().run(plugin, location, body(s, task, false));
        return s;
    }

    @Override
    public ScheduledTask runAtLater(Location location, Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getRegionScheduler().runDelayed(plugin, location, body(s, task, false), atLeastOne(delayTicks));
        return s;
    }

    @Override
    public ScheduledTask runEntity(Entity entity, Runnable task) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = entity.getScheduler().run(plugin, body(s, task, false), () -> tasks.remove(s));
        if (s.handle == null) {
            tasks.remove(s);
            return ScheduledTask.NOOP;
        }
        return s;
    }

    @Override
    public ScheduledTask runEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = entity.getScheduler().runDelayed(plugin, body(s, task, false), () -> tasks.remove(s), atLeastOne(delayTicks));
        if (s.handle == null) {
            tasks.remove(s);
            return ScheduledTask.NOOP;
        }
        return s;
    }

    @Override
    public ScheduledTask runEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = entity.getScheduler().runAtFixedRate(plugin, body(s, task, true), () -> tasks.remove(s),
                atLeastOne(delayTicks), atLeastOne(periodTicks));
        if (s.handle == null) {
            tasks.remove(s);
            return ScheduledTask.NOOP;
        }
        return s;
    }

    @Override
    public void executeEntity(Entity entity, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            runEntity(entity, task);
        }
    }

    @Override
    public void executeAt(Location location, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            task.run();
        } else {
            runAt(location, task);
        }
    }

    @Override
    public void executeGlobal(Runnable task) {
        if (Bukkit.isGlobalTickThread()) {
            task.run();
        } else {
            runGlobal(task);
        }
    }

    @Override
    public ScheduledTask runAsync(Runnable task) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getAsyncScheduler().runNow(plugin, body(s, task, false));
        return s;
    }

    @Override
    public ScheduledTask runAsyncLater(Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getAsyncScheduler().runDelayed(plugin, body(s, task, false), Math.max(1, delayTicks) * 50L, TimeUnit.MILLISECONDS);
        return s;
    }

    @Override
    public ScheduledTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) return ScheduledTask.NOOP;
        FoliaScheduled s = track();
        s.handle = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, body(s, task, true),
                Math.max(1, delayTicks) * 50L, Math.max(1, periodTicks) * 50L, TimeUnit.MILLISECONDS);
        return s;
    }

    @Override
    public boolean isOwnedByCurrentThread(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    @Override
    public boolean isOwnedByCurrentThread(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    @Override
    public boolean isGlobalThread() {
        return Bukkit.isGlobalTickThread();
    }

    @Override
    public void teleport(Entity entity, Location location) {
        entity.teleportAsync(location);
    }

    @Override
    public void cancelAll() {
        for (FoliaScheduled task : tasks) {
            task.cancel();
        }
        tasks.clear();
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    @Override
    public Platform platform() {
        return Platform.FOLIA;
    }
}
