package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.AllyHook;
import dev.ultimatecombat.core.integration.Integration;
import org.bukkit.entity.Player;

/**
 * SuperiorSkyblock2: members of the same island are allies.
 */
final class SuperiorSkyblockHook implements Integration, AllyHook {

    private Class<?> api;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        api = Reflect.type("com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI");
        return true;
    }

    @Override
    public String features() {
        return "island allies";
    }

    private Object island(Player p) {
        Object sp = Reflect.callStatic(api, "getPlayer", p);
        return sp == null ? null : Reflect.call(sp, "getIsland");
    }

    @Override
    public boolean areAllies(Player a, Player b) {
        Object ia = island(a);
        return ia != null && ia.equals(island(b));
    }
}
