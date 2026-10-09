package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.PlayerStateHook;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * LibsDisguises: detects and removes disguises (restrictions: "remove disguise in combat").
 */
final class LibsDisguisesHook implements Integration, PlayerStateHook {

    private Class<?> api;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        api = Reflect.type("me.libraryaddict.disguise.DisguiseAPI");
        return true;
    }

    @Override
    public String features() {
        return "disguises";
    }

    @Override
    public boolean isDisguised(Player player) {
        return Boolean.TRUE.equals(Reflect.callStatic(api, "isDisguised", (Entity) player));
    }

    @Override
    public void undisguise(Player player) {
        Reflect.callStatic(api, "undisguiseToAll", (Entity) player);
    }
}
