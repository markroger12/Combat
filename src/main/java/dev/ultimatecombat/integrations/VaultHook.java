package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.EconomyHook;
import dev.ultimatecombat.core.integration.Integration;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault economy provider (money rewards, bounties, penalties, head selling).
 */
final class VaultHook implements Integration, EconomyHook {

    private Economy economy;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null || rsp.getProvider() == null) {
            plugin.getLogger().warning("Vault is installed but no economy plugin is registered with it - money features stay disabled.");
            return false;
        }
        economy = rsp.getProvider();
        return true;
    }

    @Override
    public String features() {
        return "economy via " + economy.getName();
    }

    @Override
    public double balance(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    @Override
    public String withdraw(OfflinePlayer player, double amount) {
        EconomyResponse r = economy.withdrawPlayer(player, amount);
        return r != null && r.transactionSuccess() ? null : r == null ? "no response" : String.valueOf(r.errorMessage);
    }

    @Override
    public String deposit(OfflinePlayer player, double amount) {
        EconomyResponse r = economy.depositPlayer(player, amount);
        return r != null && r.transactionSuccess() ? null : r == null ? "no response" : String.valueOf(r.errorMessage);
    }

    @Override
    public String format(double amount) {
        return economy.format(amount);
    }
}
