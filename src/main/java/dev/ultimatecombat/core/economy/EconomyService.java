package dev.ultimatecombat.core.economy;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.EconomyHook;
import dev.ultimatecombat.core.util.Numbers;
import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safe money operations used by every feature (kill rewards, penalties, bounties, head trophies,
 * StatTrak costs). Safety rules from economy.yml (minimum balance protection, maximum deduction,
 * transfer instead of creating money) are enforced here, in one place.
 *
 * <p>Duplicate-payment prevention: operations that represent a unique business event (a kill,
 * a bounty claim) carry a transaction id; an id is executed at most once within the retention
 * window.</p>
 */
public final class EconomyService {

    private static final long TX_RETENTION = 600_000;

    private final UltimateCombatPlugin plugin;
    private final Map<String, Long> processed = new ConcurrentHashMap<>();
    private volatile Settings settings = new Settings(0, 0, true, 2);

    /**
     * economy.yml -> safety.
     *
     * @param minimumBalance       a penalty never takes the balance below this amount
     * @param maximumDeduction     upper limit for a single penalty (0 = unlimited)
     * @param logTransactions      log money operations to the debug log
     * @param decimals             rounding of computed amounts
     */
    public record Settings(double minimumBalance, double maximumDeduction, boolean logTransactions, int decimals) {
    }

    public EconomyService(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ConfigReader r = plugin.configs().file("economy.yml").reader().section("safety");
        settings = new Settings(
                r.decimal("minimum-balance", 0, 0, Double.MAX_VALUE),
                r.decimal("maximum-deduction", 0, 0, Double.MAX_VALUE),
                r.bool("log-transactions", true),
                r.integer("decimals", 2, 0, 6));
    }

    public Settings settings() {
        return settings;
    }

    public boolean available() {
        return plugin.integrations().economy() != null;
    }

    private EconomyHook hook() {
        return plugin.integrations().economy();
    }

    public double balance(OfflinePlayer player) {
        EconomyHook h = hook();
        return h == null ? 0 : h.balance(player);
    }

    public String format(double amount) {
        EconomyHook h = hook();
        try {
            return h == null ? Numbers.money(amount) : h.format(amount);
        } catch (RuntimeException e) {
            return Numbers.money(amount);
        }
    }

    /**
     * Records a transaction id. Returns {@code false} if it was already processed.
     */
    public boolean once(String transactionId) {
        if (transactionId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        processed.values().removeIf(t -> now - t > TX_RETENTION);
        return processed.putIfAbsent(transactionId, now) == null;
    }

    /**
     * Computes a penalty: {@code percent} of the balance (0..1) or a fixed amount, limited by the
     * maximum deduction and the minimum balance protection.
     */
    public double computePenalty(OfflinePlayer player, boolean percentMode, double value, double maxForThisPenalty) {
        double balance = balance(player);
        double amount = percentMode ? balance * value : value;
        Settings s = settings;
        if (s.maximumDeduction() > 0) {
            amount = Math.min(amount, s.maximumDeduction());
        }
        if (maxForThisPenalty > 0) {
            amount = Math.min(amount, maxForThisPenalty);
        }
        amount = Math.min(amount, Math.max(0, balance - s.minimumBalance()));
        return Math.max(0, Numbers.round(amount, s.decimals()));
    }

    /**
     * Takes an exact amount.
     *
     * @return {@code true} on success
     */
    public boolean withdraw(OfflinePlayer player, double amount, String reason) {
        EconomyHook h = hook();
        if (h == null || amount <= 0) {
            return amount == 0;
        }
        String error;
        try {
            error = h.withdraw(player, amount);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        log("withdraw", player, amount, reason, error);
        return error == null;
    }

    public boolean deposit(OfflinePlayer player, double amount, String reason) {
        EconomyHook h = hook();
        if (h == null || amount <= 0) {
            return amount == 0;
        }
        String error;
        try {
            error = h.deposit(player, amount);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        log("deposit", player, amount, reason, error);
        return error == null;
    }

    /**
     * Moves money between players. If the deposit fails the withdrawal is refunded, so money is
     * never created or destroyed.
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount, String reason) {
        if (amount <= 0) {
            return true;
        }
        if (!withdraw(from, amount, reason)) {
            return false;
        }
        if (!deposit(to, amount, reason)) {
            if (!deposit(from, amount, "refund: " + reason)) {
                plugin.getLogger().severe("Economy refund of " + amount + " to " + from.getName()
                        + " FAILED after a failed transfer (" + reason + "). Please refund manually.");
            }
            return false;
        }
        return true;
    }

    private void log(String op, OfflinePlayer player, double amount, String reason, String error) {
        if (error != null) {
            plugin.getLogger().warning("Economy " + op + " of " + amount + " for " + player.getName() + " failed: " + error
                    + " (" + reason + ")");
        } else if (settings.logTransactions()) {
            plugin.debug().log("economy", op + " " + amount + " " + player.getName() + " (" + reason + ")");
        }
    }
}
