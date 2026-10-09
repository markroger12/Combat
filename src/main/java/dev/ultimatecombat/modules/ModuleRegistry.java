package dev.ultimatecombat.modules;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.module.ModuleManager;
import dev.ultimatecombat.modules.admin.AdminMenuModule;
import dev.ultimatecombat.modules.antiabuse.AntiAbuseModule;
import dev.ultimatecombat.modules.apples.AppleModule;
import dev.ultimatecombat.modules.borders.BorderModule;
import dev.ultimatecombat.modules.bounties.BountyModule;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combatlog.CombatLogModule;
import dev.ultimatecombat.modules.corpses.CorpseModule;
import dev.ultimatecombat.modules.customtools.CustomToolModule;
import dev.ultimatecombat.modules.death.DeathModule;
import dev.ultimatecombat.modules.deathmessages.DeathMessageModule;
import dev.ultimatecombat.modules.economy.EconomyModule;
import dev.ultimatecombat.modules.enchantments.EnchantmentModule;
import dev.ultimatecombat.modules.ghosts.GhostModule;
import dev.ultimatecombat.modules.grace.GraceModule;
import dev.ultimatecombat.modules.heads.HeadModule;
import dev.ultimatecombat.modules.healthbars.HealthbarModule;
import dev.ultimatecombat.modules.indicators.DamageIndicatorModule;
import dev.ultimatecombat.modules.intervals.PvpIntervalModule;
import dev.ultimatecombat.modules.killstreaks.KillstreakModule;
import dev.ultimatecombat.modules.leaderboards.LeaderboardModule;
import dev.ultimatecombat.modules.loot.LootModule;
import dev.ultimatecombat.modules.knockback.KnockbackModule;
import dev.ultimatecombat.modules.oldpvp.OldPvpModule;
import dev.ultimatecombat.modules.placeholders.PlaceholderModule;
import dev.ultimatecombat.modules.potions.PotionModule;
import dev.ultimatecombat.modules.recaps.KillRecapModule;
import dev.ultimatecombat.modules.rewards.RewardsModule;
import dev.ultimatecombat.modules.spawn.SpawnpointModule;
import dev.ultimatecombat.modules.stats.StatsModule;
import dev.ultimatecombat.modules.stattrak.StatTrakModule;
import dev.ultimatecombat.modules.togglepvp.TogglePvpModule;
import dev.ultimatecombat.modules.totems.TotemModule;
import dev.ultimatecombat.modules.warmups.WarmupModule;
import dev.ultimatecombat.modules.restrictions.RestrictionsModule;

/**
 * Registers every feature module in dependency order.
 */
public final class ModuleRegistry {

    private ModuleRegistry() {
    }

    public static void registerAll(UltimateCombatPlugin plugin, ModuleManager modules) {
        modules.register(new CombatModule(plugin));
        modules.register(new RestrictionsModule(plugin));
        modules.register(new CombatLogModule(plugin));
        modules.register(new GhostModule(plugin));
        modules.register(new BorderModule(plugin));
        modules.register(new OldPvpModule(plugin));
        modules.register(new KnockbackModule(plugin));
        modules.register(new TogglePvpModule(plugin));
        modules.register(new GraceModule(plugin));
        modules.register(new AntiAbuseModule(plugin));
        modules.register(new StatsModule(plugin));
        modules.register(new KillstreakModule(plugin));
        modules.register(new EconomyModule(plugin));
        modules.register(new RewardsModule(plugin));
        modules.register(new LootModule(plugin));
        modules.register(new SpawnpointModule(plugin));
        modules.register(new DeathModule(plugin));
        modules.register(new PotionModule(plugin));
        modules.register(new EnchantmentModule(plugin));
        modules.register(new AppleModule(plugin));
        modules.register(new TotemModule(plugin));
        modules.register(new PvpIntervalModule(plugin));
        modules.register(new WarmupModule(plugin));
        modules.register(new HeadModule(plugin));
        modules.register(new BountyModule(plugin));
        modules.register(new CorpseModule(plugin));
        modules.register(new StatTrakModule(plugin));
        modules.register(new KillRecapModule(plugin));
        modules.register(new DamageIndicatorModule(plugin));
        modules.register(new HealthbarModule(plugin));
        modules.register(new DeathMessageModule(plugin));
        modules.register(new CustomToolModule(plugin));
        modules.register(new LeaderboardModule(plugin));
        modules.register(new PlaceholderModule(plugin));
        modules.register(new AdminMenuModule(plugin));
    }
}
