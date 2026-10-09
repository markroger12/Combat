package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.PlayerStateHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * EssentialsX: god mode and vanish state (used by restrictions such as "disable god mode in
 * combat" and "no tagging of vanished players").
 */
final class EssentialsHook implements Integration, PlayerStateHook {

    private Plugin essentials;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        return essentials != null;
    }

    @Override
    public String features() {
        return "god mode, vanish";
    }

    private Object user(Player player) {
        return Reflect.call(essentials, "getUser", player);
    }

    @Override
    public boolean isGodMode(Player player) {
        Object user = user(player);
        return user != null && Boolean.TRUE.equals(Reflect.call(user, "isGodModeEnabled"));
    }

    @Override
    public void setGodMode(Player player, boolean enabled) {
        Object user = user(player);
        if (user != null) {
            Reflect.call(user, "setGodModeEnabled", enabled);
        }
    }

    @Override
    public boolean isVanished(Player player) {
        Object user = user(player);
        return user != null && Boolean.TRUE.equals(Reflect.call(user, "isVanished"));
    }

    @Override
    public void setVanished(Player player, boolean vanished) {
        Object user = user(player);
        if (user != null) {
            Reflect.call(user, "setVanished", vanished);
        }
    }
}
