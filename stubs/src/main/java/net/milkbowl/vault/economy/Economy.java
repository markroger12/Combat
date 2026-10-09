package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/** Compile-time stub of Vault's economy interface (subset used by UltimateCombat). */
public interface Economy {

    boolean isEnabled();

    String getName();

    String format(double amount);

    double getBalance(OfflinePlayer player);

    EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);

    EconomyResponse depositPlayer(OfflinePlayer player, double amount);
}
