package dev.ultimatecombat.core.integration;

import org.bukkit.OfflinePlayer;

/**
 * Economy provider (Vault).
 */
public interface EconomyHook {

    double balance(OfflinePlayer player);

    /**
     * @return {@code null} on success, otherwise the provider's error message
     */
    String withdraw(OfflinePlayer player, double amount);

    /**
     * @return {@code null} on success, otherwise the provider's error message
     */
    String deposit(OfflinePlayer player, double amount);

    String format(double amount);
}
