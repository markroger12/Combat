package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.ItemHook;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * MMOItems: custom item ids ("&lt;TYPE&gt;:&lt;ID&gt;", for example "SWORD:CUTLASS") for item restrictions.
 */
final class MmoItemsHook implements Integration, ItemHook {

    private Class<?> api;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        api = Reflect.type("net.Indyuce.mmoitems.MMOItems");
        return true;
    }

    @Override
    public String features() {
        return "custom item ids";
    }

    @Override
    public String customItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        Object id = Reflect.callStatic(api, "getID", item);
        if (id == null || id.toString().isEmpty()) {
            return null;
        }
        Object type = Reflect.callStatic(api, "getTypeName", item);
        return ((type == null ? "" : type + ":") + id).toLowerCase(Locale.ROOT);
    }
}
