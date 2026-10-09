package dev.ultimatecombat.modules;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.module.ModuleManager;
import dev.ultimatecombat.modules.borders.BorderModule;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combatlog.CombatLogModule;
import dev.ultimatecombat.modules.ghosts.GhostModule;
import dev.ultimatecombat.modules.knockback.KnockbackModule;
import dev.ultimatecombat.modules.oldpvp.OldPvpModule;
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
    }
}
