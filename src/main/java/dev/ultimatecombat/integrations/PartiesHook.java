package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.AllyHook;
import dev.ultimatecombat.core.integration.Integration;
import org.bukkit.entity.Player;

/**
 * Parties (AlessioDP): members of the same party are allies.
 */
final class PartiesHook implements Integration, AllyHook {

    private Object api;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        api = Reflect.callStatic(Reflect.type("com.alessiodp.parties.api.Parties"), "getApi");
        return api != null;
    }

    @Override
    public String features() {
        return "party allies";
    }

    private Object party(Player p) {
        Object partyPlayer = Reflect.call(api, "getPartyPlayer", p.getUniqueId());
        return partyPlayer == null ? null : Reflect.call(partyPlayer, "getPartyId");
    }

    @Override
    public boolean areAllies(Player a, Player b) {
        Object pa = party(a);
        return pa != null && pa.equals(party(b));
    }
}
