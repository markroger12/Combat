package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.EntityHook;
import dev.ultimatecombat.core.integration.Integration;
import org.bukkit.entity.Entity;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * MythicMobs 5: custom mob ids (the MythicMobs internal mob name, lower case) for PvE tagging, rewards and mob filters;
 * types listed in integrations.yml count as bosses.
 */
final class MythicMobsHook implements Integration, EntityHook {

    private final Set<String> bossTypes;
    private Object mobManager;

    MythicMobsHook(Set<String> bossTypes) {
        this.bossTypes = bossTypes;
    }

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        Object inst = Reflect.callStatic(Reflect.type("io.lumine.mythic.bukkit.MythicBukkit"), "inst");
        mobManager = Reflect.call(inst, "getMobManager");
        return mobManager != null;
    }

    @Override
    public String features() {
        return "custom mob ids" + (bossTypes.isEmpty() ? "" : ", " + bossTypes.size() + " boss type(s)");
    }

    private String type(Entity entity) {
        Object result = Reflect.call(mobManager, "getActiveMob", entity.getUniqueId());
        if (result instanceof Optional<?> opt && opt.isPresent()) {
            Object mob = opt.get();
            return String.valueOf(Reflect.call(mob, "getMobType"));
        }
        return null;
    }

    @Override
    public String customId(Entity entity) {
        String type = type(entity);
        return type == null ? null : type.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean isBoss(Entity entity) {
        if (bossTypes.isEmpty()) {
            return false;
        }
        String type = type(entity);
        return type != null && bossTypes.contains(type.toLowerCase(Locale.ROOT));
    }
}
