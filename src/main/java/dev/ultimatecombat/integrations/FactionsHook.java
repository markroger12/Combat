package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.AllyHook;
import dev.ultimatecombat.core.integration.Integration;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;

/**
 * FactionsUUID-style Factions (com.massivecraft.factions.FPlayers API, also used by SaberFactions):
 * players whose relation is one of the configured relations (default MEMBER, ALLY) are allies.
 */
final class FactionsHook implements Integration, AllyHook {

    private final Set<String> relations;
    private Object fplayers;

    FactionsHook(Set<String> relations) {
        this.relations = relations;
    }

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        fplayers = Reflect.callStatic(Reflect.type("com.massivecraft.factions.FPlayers"), "getInstance");
        return fplayers != null;
    }

    @Override
    public String features() {
        return "faction allies (" + String.join(", ", relations) + ")";
    }

    @Override
    public boolean areAllies(Player a, Player b) {
        Object fa = Reflect.call(fplayers, "getByPlayer", a);
        Object fb = Reflect.call(fplayers, "getByPlayer", b);
        if (fa == null || fb == null) {
            return false;
        }
        Object faction = Reflect.call(fa, "getFaction");
        if (faction != null && Boolean.TRUE.equals(Reflect.call(faction, "isWilderness"))) {
            return false; // factionless players are never allies
        }
        Object relation = Reflect.call(fa, "getRelationTo", fb);
        return relation instanceof Enum<?> e && relations.contains(e.name().toUpperCase(Locale.ROOT));
    }
}
