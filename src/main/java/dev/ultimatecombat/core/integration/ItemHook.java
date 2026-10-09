package dev.ultimatecombat.core.integration;

import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Custom item / custom enchantment provider (MMOItems, AdvancedEnchantments, EcoEnchants, ...).
 */
public interface ItemHook {

    /**
     * @return custom item id such as {@code SWORD:FIRE_BLADE}, or {@code null}
     */
    default String customItemId(ItemStack item) {
        return null;
    }

    /**
     * @return custom enchantments on the item (lower case id -> level)
     */
    default Map<String, Integer> customEnchants(ItemStack item) {
        return Map.of();
    }

    /**
     * Removes a custom enchantment.
     *
     * @return the modified item (may be the same instance)
     */
    default ItemStack removeCustomEnchant(ItemStack item, String id) {
        return item;
    }
}
