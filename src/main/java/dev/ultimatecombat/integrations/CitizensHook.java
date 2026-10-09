package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.NpcHook;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Citizens: player-shaped ghost NPCs for combat loggers (ghosts.yml, npc-type auto/citizens).
 * NPCs are created in an anonymous in-memory registry, so they are never written to Citizens'
 * save file and disappear completely on restart.
 *
 * <p>Ghosts do not fight back through Citizens; Sentinel is not integrated.</p>
 */
final class CitizensHook implements Integration, NpcHook {

    private UltimateCombatPlugin plugin;
    private Object registry;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) throws Exception {
        this.plugin = plugin;
        Class<?> api = Reflect.type("net.citizensnpcs.api.CitizensAPI");
        Object store = Reflect.type("net.citizensnpcs.api.npc.MemoryNPCDataStore").getConstructor().newInstance();
        registry = Reflect.callStatic(api, "createAnonymousNPCRegistry", store);
        return registry != null;
    }

    @Override
    public void disable() {
        if (registry != null) {
            try {
                Reflect.call(registry, "deregisterAll");
            } catch (RuntimeException ignored) {
                // Citizens is shutting down
            }
        }
    }

    @Override
    public String features() {
        return "ghost NPCs (in-memory registry)";
    }

    @Override
    public Handle spawn(Player template, Location location, String name, boolean fightBack) {
        Object npc = Reflect.call(registry, "createNPC", EntityType.PLAYER, name);
        try {
            Reflect.call(npc, "setProtected", false);
        } catch (RuntimeException e) {
            plugin.debug().log("integrations", "Citizens setProtected failed: " + e);
        }
        try {
            Object skinTrait = Reflect.call(npc, "getOrAddTrait", Reflect.type("net.citizensnpcs.trait.SkinTrait"));
            Reflect.call(skinTrait, "setSkinName", template.getName());
        } catch (RuntimeException e) {
            plugin.debug().log("integrations", "Citizens skin could not be set: " + e);
        }
        if (fightBack) {
            plugin.debug().log("integrations", "ghosts fight-back is not supported with Citizens NPCs (no Sentinel integration)");
        }
        if (!Boolean.TRUE.equals(Reflect.call(npc, "spawn", location))) {
            Reflect.call(npc, "destroy");
            return null;
        }
        return new Handle() {
            @Override
            public Entity entity() {
                return (Entity) Reflect.call(npc, "getEntity");
            }

            @Override
            public boolean isSpawned() {
                return Boolean.TRUE.equals(Reflect.call(npc, "isSpawned"));
            }

            @Override
            public void destroy() {
                Reflect.call(npc, "destroy");
            }
        };
    }
}
