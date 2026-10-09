package dev.ultimatecombat.core.integration;

import dev.ultimatecombat.UltimateCombatPlugin;

/**
 * An optional hook into another plugin. Implementations may reference the other plugin's classes;
 * they are only instantiated after that plugin was found enabled, so a missing dependency can never
 * cause class loading errors.
 */
public interface Integration {

    /**
     * Initialises the hook.
     *
     * @return {@code true} when the hook is active
     * @throws Exception any failure; the integration is then reported as failed and skipped
     */
    boolean enable(UltimateCombatPlugin plugin) throws Exception;

    default void disable() {
    }

    /**
     * Short human readable list of what this hook provides (for /uc integrations).
     */
    String features();
}
