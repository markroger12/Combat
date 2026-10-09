package dev.ultimatecombat.modules.economy;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.economy.EconomyService;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.module.Module;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * Money for kills and money lost on death (economy.yml). The safety rules of the same file are
 * enforced by {@link EconomyService} for every money operation of the plugin.
 */
public final class EconomyModule extends Module<EconomyModule.Settings> implements KillHandler {

    public record Settings(boolean killReward, boolean killPercent, double killAmount, double killMaximum, boolean killFromVictim,
                           boolean deathPenalty, boolean deathPercent, double deathAmount, double deathMaximum, boolean deathPvpOnly,
                           boolean deathToKiller, boolean notifyPlayers) {
    }

    private boolean registeredHandler;

    public EconomyModule(UltimateCombatPlugin plugin) {
        super(plugin, "economy", "economy.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        ConfigReader k = r.section("kill-reward");
        ConfigReader d = r.section("death-penalty");
        return new Settings(
                k.bool("enabled", false),
                k.choice("mode", "fixed", "fixed", "percent").equals("percent"),
                k.decimal("amount", 50, 0, Double.MAX_VALUE),
                k.decimal("maximum", 0, 0, Double.MAX_VALUE),
                k.choice("source", "server", "server", "victim").equals("victim"),
                d.bool("enabled", false),
                d.choice("mode", "percent", "fixed", "percent").equals("percent"),
                d.decimal("amount", 5, 0, Double.MAX_VALUE),
                d.decimal("maximum", 0, 0, Double.MAX_VALUE),
                d.bool("pvp-only", true),
                d.bool("give-to-killer", false),
                r.bool("notify", true));
    }

    @Override
    protected void onEnable() {
        if (!registeredHandler) {
            plugin.kills().register(this);
            registeredHandler = true;
        }
        if (!plugin.economy().available()) {
            plugin.getLogger().info("economy.yml: no Vault economy found - money rewards and penalties stay inactive until one is installed.");
        }
    }

    @Override
    public boolean activeHandler() {
        return isEnabled();
    }

    @Override
    public int killPriority() {
        return ECONOMY;
    }

    @Override
    public void onPlayerDeath(KillContext ctx) {
        EconomyService eco = plugin.economy();
        if (!eco.available()) {
            return;
        }
        Player victim = ctx.victim();
        Player killer = ctx.killer();
        Settings s = settings(victim.getWorld());
        // One transaction id per real death prevents duplicate payments.
        String tx = ctx.event() != null ? "death:" + victim.getUniqueId() + ":" + victim.getStatistic(Statistic.DEATHS)
                : "synthetic:" + victim.getUniqueId() + ":" + System.nanoTime();
        if (!eco.once(tx)) {
            return;
        }
        if (s.deathPenalty() && (ctx.isPvp() || !s.deathPvpOnly())) {
            // Suppressed (abusive) kills never pay the killer; the penalty is then simply removed.
            boolean toKiller = s.deathToKiller() && ctx.isPvp() && !ctx.rewardsSuppressed();
            double amount = eco.computePenalty(victim, s.deathPercent(), s.deathPercent() ? s.deathAmount() / 100.0 : s.deathAmount(),
                    s.deathMaximum());
            if (amount > 0) {
                boolean ok;
                if (toKiller) {
                    ok = eco.transfer(victim, killer, amount, "death penalty");
                    if (ok && s.notifyPlayers()) {
                        plugin.messages().send(killer, "economy.penalty-received", "amount", eco.format(amount), "player", victim.getName());
                    }
                } else {
                    ok = eco.withdraw(victim, amount, "death penalty");
                }
                if (ok) {
                    ctx.moneyTransferred(ctx.moneyTransferred() + amount);
                    if (s.notifyPlayers()) {
                        plugin.messages().send(victim, "economy.penalty-lost", "amount", eco.format(amount));
                    }
                }
            }
        }
        if (!ctx.isPvp() || ctx.rewardsSuppressed() || !s.killReward()) {
            return;
        }
        double amount;
        if (s.killPercent()) {
            double base = s.killFromVictim() ? eco.balance(victim) : eco.balance(killer);
            amount = base * s.killAmount() / 100.0;
        } else {
            amount = s.killAmount();
        }
        if (s.killMaximum() > 0) {
            amount = Math.min(amount, s.killMaximum());
        }
        if (s.killFromVictim()) {
            amount = eco.computePenalty(victim, false, amount, 0);
        }
        if (amount <= 0) {
            return;
        }
        boolean ok = s.killFromVictim() ? eco.transfer(victim, killer, amount, "kill reward") : eco.deposit(killer, amount, "kill reward");
        if (ok) {
            ctx.moneyTransferred(ctx.moneyTransferred() + amount);
            if (s.notifyPlayers()) {
                plugin.messages().send(killer, "economy.kill-reward", "amount", eco.format(amount), "victim", victim.getName());
            }
        }
    }
}
