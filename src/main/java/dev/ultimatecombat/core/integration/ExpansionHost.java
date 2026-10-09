package dev.ultimatecombat.core.integration;

/**
 * Integration that registers UltimateCombat's own placeholder expansion (PlaceholderAPI).
 */
public interface ExpansionHost {

    /**
     * Registers the expansion again (or unregisters it when the placeholders module is disabled).
     */
    void registerExpansion();

    /**
     * Removes the expansion (placeholders module disabled).
     */
    void unregisterExpansion();
}
