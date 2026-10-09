package dev.ultimatecombat.support;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Vault economy registered under a mock "Vault" plugin, so economy features can be
 * tested exactly like on a server with Vault and an economy plugin.
 */
public final class TestEconomy implements Economy {

    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();

    public static TestEconomy install(ServerMock server) {
        PluginMock vault = MockBukkit.createMockPlugin("Vault");
        TestEconomy economy = new TestEconomy();
        server.getServicesManager().register(Economy.class, economy, vault, ServicePriority.Normal);
        return economy;
    }

    private static final class Response extends EconomyResponse {
        private final boolean success;

        Response(boolean success, String error) {
            super(error);
            this.success = success;
        }

        @Override
        public boolean transactionSuccess() {
            return success;
        }
    }

    public void set(OfflinePlayer player, double amount) {
        balances.put(player.getUniqueId(), amount);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "TestEconomy";
    }

    @Override
    public String format(double amount) {
        return String.format(java.util.Locale.ROOT, "$%.2f", amount);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return balances.getOrDefault(player.getUniqueId(), 0.0);
    }

    @Override
    public synchronized EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        double balance = getBalance(player);
        if (amount < 0 || balance < amount) {
            return new Response(false, "insufficient funds");
        }
        balances.put(player.getUniqueId(), balance - amount);
        return new Response(true, null);
    }

    @Override
    public synchronized EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new Response(false, "negative amount");
        }
        balances.put(player.getUniqueId(), getBalance(player) + amount);
        return new Response(true, null);
    }
}
