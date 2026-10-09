package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.AllyHook;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.ProtectionHook;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Towny: PvP status of town blocks/worlds and town/nation allies (reflection against TownyAPI and
 * CombatUtil).
 */
final class TownyHook implements Integration, ProtectionHook, AllyHook {

    private Object api;
    private Class<?> combatUtil;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        api = Reflect.callStatic(Reflect.type("com.palmergames.bukkit.towny.TownyAPI"), "getInstance");
        combatUtil = Reflect.type("com.palmergames.bukkit.towny.utils.CombatUtil");
        return api != null;
    }

    @Override
    public String features() {
        return "town pvp, allies";
    }

    @Override
    public boolean isPvpAllowed(Location location) {
        return Boolean.TRUE.equals(Reflect.call(api, "isPVP", location));
    }

    @Override
    public boolean areAllies(Player a, Player b) {
        return Boolean.TRUE.equals(Reflect.callStatic(combatUtil, "isAlly", a.getName(), b.getName()));
    }
}
