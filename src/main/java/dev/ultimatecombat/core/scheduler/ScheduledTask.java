package dev.ultimatecombat.core.scheduler;

/**
 * Platform-neutral handle to a scheduled task.
 */
public interface ScheduledTask {

    ScheduledTask NOOP = new ScheduledTask() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();
}
