package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.ProtectionHook;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WorldGuard 7: the "pvp" region flag and region ids (used by region-based settings such as
 * blocked/forced regions, combat borders and PvP intervals). Implemented against WorldGuard 7's
 * public API via reflection.
 */
final class WorldGuardHook implements Integration, ProtectionHook {

    private Object regionContainer;
    private Object pvpFlag;
    private Class<?> stateFlagClass;
    private Method adaptLocation;
    private Object wgPlugin;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) throws Exception {
        Class<?> wg = Reflect.type("com.sk89q.worldguard.WorldGuard");
        Object instance = Reflect.callStatic(wg, "getInstance");
        regionContainer = Reflect.call(Reflect.call(instance, "getPlatform"), "getRegionContainer");
        pvpFlag = Reflect.staticField(Reflect.type("com.sk89q.worldguard.protection.flags.Flags"), "PVP");
        stateFlagClass = Reflect.type("com.sk89q.worldguard.protection.flags.StateFlag");
        adaptLocation = Reflect.type("com.sk89q.worldedit.bukkit.BukkitAdapter").getMethod("adapt", Location.class);
        wgPlugin = Reflect.callStatic(Reflect.type("com.sk89q.worldguard.bukkit.WorldGuardPlugin"), "inst");
        return true;
    }

    @Override
    public String features() {
        return "pvp flag, region ids";
    }

    private Object regions(Location location) throws ReflectiveOperationException {
        Object query = Reflect.call(regionContainer, "createQuery");
        return Reflect.call(query, "getApplicableRegions", adaptLocation.invoke(null, location));
    }

    private boolean allowed(Location location, Player subject) {
        try {
            Object set = regions(location);
            Object flags = Array.newInstance(stateFlagClass, 1);
            Array.set(flags, 0, pvpFlag);
            Object associable = subject == null ? null : Reflect.call(wgPlugin, "wrapPlayer", subject);
            Method queryState = null;
            for (Method m : set.getClass().getMethods()) {
                if (m.getName().equals("queryState") && m.getParameterCount() == 2 && m.getParameterTypes()[1].isArray()) {
                    queryState = m;
                    break;
                }
            }
            if (queryState == null) {
                throw new IllegalStateException("ApplicableRegionSet.queryState not found");
            }
            Object state = queryState.invoke(set, associable, flags);
            return state == null || !"DENY".equals(((Enum<?>) state).name());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WorldGuard query failed: " + e, e);
        }
    }

    @Override
    public boolean isPvpAllowed(Location location) {
        return allowed(location, null);
    }

    @Override
    public boolean canAttack(Player attacker, Player victim) {
        return allowed(attacker.getLocation(), attacker) && allowed(victim.getLocation(), victim);
    }

    @Override
    public Set<String> regionsAt(Location location) {
        try {
            Set<String> out = new LinkedHashSet<>();
            Object set = regions(location);
            for (Object region : (Iterable<?>) Reflect.call(set, "getRegions")) {
                out.add(String.valueOf(Reflect.call(region, "getId")));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WorldGuard region lookup failed: " + e, e);
        }
    }
}
