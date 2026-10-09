package dev.ultimatecombat.core.util;

import dev.ultimatecombat.core.message.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent item creation used by menus, trophies and compasses.
 */
public final class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this(new ItemStack(material == null ? Material.STONE : material));
    }

    public ItemBuilder(ItemStack item) {
        this.item = item.clone();
        this.meta = this.item.getItemMeta();
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder name(String name) {
        if (meta != null && name != null) {
            meta.setDisplayName(Text.color(name));
        }
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder lore(List<String> lore) {
        if (meta != null && lore != null) {
            List<String> colored = new ArrayList<>(lore.size());
            for (String line : lore) {
                colored.add(Text.color(line));
            }
            meta.setLore(colored);
        }
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder addLore(List<String> lore) {
        if (meta != null && lore != null && !lore.isEmpty()) {
            List<String> current = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            for (String line : lore) {
                current.add(Text.color(line));
            }
            meta.setLore(current);
        }
        return this;
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder customModelData(int data) {
        if (meta != null && data > 0) {
            meta.setCustomModelData(data);
        }
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        if (meta != null && glow) {
            Enchantment unbreaking = Registries.enchantment("unbreaking");
            if (unbreaking != null) {
                meta.addEnchant(unbreaking, 1, true);
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        return this;
    }

    public ItemBuilder hideAttributes() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
        }
        return this;
    }

    public ItemBuilder skull(OfflinePlayer owner) {
        if (meta instanceof SkullMeta skull && owner != null) {
            skull.setOwningPlayer(owner);
        }
        return this;
    }

    public ItemBuilder edit(java.util.function.Consumer<ItemMeta> editor) {
        if (meta != null) {
            editor.accept(meta);
        }
        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack named(Material material, String name) {
        return new ItemBuilder(material).name(name).build();
    }

    public static OfflinePlayer offline(java.util.UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }
}
