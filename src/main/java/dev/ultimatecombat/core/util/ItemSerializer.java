package dev.ultimatecombat.core.util;

import dev.ultimatecombat.core.scheduler.Platform;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Converts items to text for the database. Paper's byte format is preferred (it is upgraded by
 * the server's data fixers between Minecraft versions); Spigot falls back to YAML serialisation.
 * Each entry is tagged so data written on one platform can still be read after switching.
 */
public final class ItemSerializer {

    private static final boolean PAPER_BYTES = hasPaperBytes();

    private ItemSerializer() {
    }

    private static boolean hasPaperBytes() {
        try {
            ItemStack.class.getMethod("serializeAsBytes");
            ItemStack.class.getMethod("deserializeBytes", byte[].class);
            return Platform.current().isPaperBased();
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static String serialize(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return "";
        }
        if (PAPER_BYTES) {
            try {
                return "P:" + Base64.getEncoder().encodeToString(item.serializeAsBytes());
            } catch (RuntimeException ignored) {
                // fall back to YAML
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("i", item);
        return "Y:" + Base64.getEncoder().encodeToString(yaml.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static ItemStack deserialize(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        if (data.startsWith("P:")) {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data.substring(2)));
        }
        if (data.startsWith("Y:")) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(Base64.getDecoder().decode(data.substring(2)), java.nio.charset.StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IllegalArgumentException("Corrupt item data", e);
            }
            return yaml.getItemStack("i");
        }
        throw new IllegalArgumentException("Unknown item data format");
    }

    /**
     * Serialises an array (with empty slots) into one string separated by ';'.
     */
    public static String serializeArray(ItemStack[] items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(serialize(items[i]));
        }
        return sb.toString();
    }

    public static ItemStack[] deserializeArray(String data) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[0];
        }
        String[] parts = data.split(";", -1);
        ItemStack[] out = new ItemStack[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = deserialize(parts[i]);
        }
        return out;
    }

    public static List<ItemStack> nonEmpty(ItemStack[] items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                out.add(item);
            }
        }
        return out;
    }
}
