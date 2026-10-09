package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.ItemHook;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AdvancedEnchantments: custom enchantments on items (enchantments.yml limits/removals apply to
 * them as "custom:&lt;name&gt;").
 */
final class AdvancedEnchantmentsHook implements Integration, ItemHook {

    private Class<?> api;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        api = Reflect.type("net.advancedplugins.ae.api.AEAPI");
        return true;
    }

    @Override
    public String features() {
        return "custom enchantments";
    }

    @Override
    public Map<String, Integer> customEnchants(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Map.of();
        }
        Object result = Reflect.callStatic(api, "getEnchantmentsOnItem", item);
        Map<String, Integer> out = new HashMap<>();
        if (result instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    out.put(e.getKey().toString().toLowerCase(Locale.ROOT), n.intValue());
                }
            }
        }
        return out;
    }

    @Override
    public ItemStack removeCustomEnchant(ItemStack item, String id) {
        if (!customEnchants(item).containsKey(id.toLowerCase(Locale.ROOT))) {
            return item;
        }
        Object result = Reflect.callStatic(api, "removeEnchantment", item, id);
        return result instanceof ItemStack stack ? stack : item;
    }
}
