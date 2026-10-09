package dev.ultimatecombat.core.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduler for single-main-thread servers (Spigot and Paper).
 */
public final class BukkitTaskScheduler implements TaskScheduler {

    private final Plugin plugin;
    private final Platform platform;
    private final Set<BukkitScheduled> tasks = ConcurrentHashMap.newKeySet();

    public BukkitTaskScheduler(Plugin plugin, Platform platform) {
        this.plugin = plugin;
        this.platform = platform;
    }

    private final class BukkitScheduled implements ScheduledTask {
        private volatile BukkitTask handle;
        private volatile boolean cancelled;
        private final boolean repeating;

        BukkitScheduled(boolean repeating) {
            this.repeating = repeating;
        }

        @Override
        public void cancel() {
            cancelled = true;
            BukkitTask h = handle;
            if (h != null) {
                h.cancel();
            }
            tasks.remove(this);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        void finishIfOneShot() {
            if (!repeating) {
                tasks.remove(this);
            }
        }
    }

    private Runnable wrap(BukkitScheduled scheduled, Runnable task) {
        return () -> {
            if (scheduled.cancelled) {
                return;
            }
            try {
                task.run();
            } finally {
                scheduled.finishIfOneShot();
            }
        };
    }

    private ScheduledTask sync(Runnable task, long delay, long period) {
        if (!plugin.isEnabled()) {
            return ScheduledTask.NOOP;
        }
        BukkitScheduled scheduled = new BukkitScheduled(period > 0);
        tasks.add(scheduled);
        Runnable wrapped = wrap(scheduled, task);
        if (period > 0) {
            scheduled.handle = Bukkit.getScheduler().runTaskTimer(plugin, wrapped, Math.max(0, delay), period);
        } else if (delay > 0) {
            scheduled.handle = Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
        } else {
            scheduled.handle = Bukkit.getScheduler().runTask(plugin, wrapped);
        }
        return scheduled;
    }

    private ScheduledTask async(Runnable task, long delay, long period) {
        if (!plugin.isEnabled()) {
            return ScheduledTask.NOOP;
        }
        BukkitScheduled scheduled = new BukkitScheduled(period > 0);
        tasks.add(scheduled);
        Runnable wrapped = wrap(scheduled, task);
        if (period > 0) {
            scheduled.handle = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, wrapped, Math.max(0, delay), period);
        } else if (delay > 0) {
            scheduled.handle = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, delay);
        } else {
            scheduled.handle = Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapped);
        }
        return scheduled;
    }

    @Override
    public ScheduledTask runGlobal(Runnable task) {
        return sync(task, 0, 0);
    }

    @Override
    public ScheduledTask runGlobalLater(Runnable task, long delayTicks) {
        return sync(task, delayTicks, 0);
    }

    @Override
    public ScheduledTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        return sync(task, delayTicks, Math.max(1, periodTicks));
    }

    @Override
    public ScheduledTask runAt(Location location, Runnable task) {
        return sync(task, 0, 0);
    }

    @Override
    public ScheduledTask runAtLater(Location location, Runnable task, long delayTicks) {
        return sync(task, delayTicks, 0);
    }

    @Override
    public ScheduledTask runEntity(Entity entity, Runnable task) {
        return sync(() -> {
            if (entity.isValid() || entity instanceof org.bukkit.entity.Player p && p.isOnline()) {
                task.run();
            }
        }, 0, 0);
    }

    @Override
    public ScheduledTask runEntityLater(Entity entity, Runnable task, long delayTicks) {
        return sync(() -> {
            if (entity.isValid() || entity instanceof org.bukkit.entity.Player p && p.isOnline()) {
                task.run();
            }
        }, delayTicks, 0);
    }

    @Override
    public ScheduledTask runEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        final ScheduledTask[] self = new ScheduledTask[1];
        self[0] = sync(() -> {
            if (entity.isValid() || entity instanceof org.bukkit.entity.Player p && p.isOnline()) {
                task.run();
            } else if (self[0] != null) {
                self[0].cancel();
            }
        }, delayTicks, Math.max(1, periodTicks));
        return self[0];
    }

    @Override
    public void executeEntity(Entity entity, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            runEntity(entity, task);
        }
    }

    @Override
    public void executeAt(Location location, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            runAt(location, task);
        }
    }

    @Override
    public void executeGlobal(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            runGlobal(task);
        }
    }

    @Override
    public ScheduledTask runAsync(Runnable task) {
        return async(task, 0, 0);
    }

    @Override
    public ScheduledTask runAsyncLater(Runnable task, long delayTicks) {
        return async(task, delayTicks, 0);
    }

    @Override
    public ScheduledTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        return async(task, delayTicks, Math.max(1, periodTicks));
    }

    @Override
    public boolean isOwnedByCurrentThread(Entity entity) {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentThread(Location location) {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isGlobalThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void teleport(Entity entity, Location location) {
        if (platform.isPaperBased()) {
            entity.teleportAsync(location);
        } else {
            executeEntity(entity, () -> entity.teleport(location));
        }
    }

    @Override
    public void cancelAll() {
        for (BukkitScheduled task : tasks) {
            task.cancel();
        }
        tasks.clear();
        Bukkit.getScheduler().cancelTasks(plugin);
    }

    @Override
    public Platform platform() {
        return platform;
    }
}
