package dev.ultimatecombat.core.gui;

import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.message.MessageService;
import dev.ultimatecombat.core.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu layout read from {@code menus.yml}: title, rows, filler and named items. Modules decide
 * what an item does by its key; administrators may additionally attach extra actions.
 */
public final class MenuTemplate {

    /**
     * One configurable menu item.
     */
    public record ItemSpec(String key, List<Integer> slots, Material material, String name, List<String> lore, int customModelData,
                           boolean glow, int amount, String permission, String sound, List<String> actions, boolean enabled) {

        public int slot() {
            return slots.isEmpty() ? -1 : slots.get(0);
        }

        public ItemStack build(MessageService messages, Player viewer, Object... placeholders) {
            List<String> resolvedLore = new ArrayList<>(lore.size());
            for (String line : lore) {
                resolvedLore.add(messages.apply(viewer, line, placeholders));
            }
            return new ItemBuilder(material).name(messages.apply(viewer, name, placeholders)).lore(resolvedLore)
                    .customModelData(customModelData).glow(glow).amount(amount).hideAttributes().build();
        }

        public ItemSpec withMaterial(Material m) {
            return new ItemSpec(key, slots, m, name, lore, customModelData, glow, amount, permission, sound, actions, enabled);
        }
    }

    private final String id;
    private final String title;
    private final int rows;
    private final ItemSpec filler;
    private final Map<String, ItemSpec> items;
    private final List<Integer> contentSlots;

    public MenuTemplate(String id, String title, int rows, ItemSpec filler, Map<String, ItemSpec> items, List<Integer> contentSlots) {
        this.id = id;
        this.title = title;
        this.rows = rows;
        this.filler = filler;
        this.items = items;
        this.contentSlots = contentSlots;
    }

    public static MenuTemplate read(String id, ConfigReader r) {
        String title = r.string("title", id);
        int rows = r.integer("rows", 6, 1, 6);
        ItemSpec filler = null;
        if (r.has("filler") && r.bool("filler.enabled", true)) {
            filler = readItem("filler", r.section("filler"), rows);
        }
        Map<String, ItemSpec> items = new LinkedHashMap<>();
        for (String key : r.keys("items")) {
            ItemSpec spec = readItem(key, r.section("items." + key), rows);
            items.put(key, spec);
        }
        List<Integer> content = parseSlots(r, "content-slots", rows);
        return new MenuTemplate(id, title, rows, filler, items, content);
    }

    private static ItemSpec readItem(String key, ConfigReader r, int rows) {
        List<Integer> slots = parseSlots(r, "slot", rows);
        if (slots.isEmpty()) {
            slots = parseSlots(r, "slots", rows);
        }
        return new ItemSpec(key, slots,
                r.material("material", Material.STONE),
                r.string("name", " "),
                r.stringList("lore"),
                r.integer("custom-model-data", 0, 0, Integer.MAX_VALUE),
                r.bool("glow", false),
                r.integer("amount", 1, 1, 64),
                r.string("permission", ""),
                r.string("sound", ""),
                r.stringList("actions"),
                r.bool("enabled", true));
    }

    /**
     * Parses slots written as a number, a list of numbers or ranges like {@code 10-16}.
     */
    static List<Integer> parseSlots(ConfigReader r, String path, int rows) {
        if (!r.has(path)) {
            return Collections.emptyList();
        }
        Object raw = r.raw(path);
        List<String> parts = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) parts.add(String.valueOf(o));
        } else {
            for (String s : String.valueOf(raw).split(",")) parts.add(s);
        }
        List<Integer> out = new ArrayList<>();
        int max = rows * 9 - 1;
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                if (t.contains("-")) {
                    String[] ab = t.split("-");
                    int a = Integer.parseInt(ab[0].trim());
                    int b = Integer.parseInt(ab[1].trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                        if (i >= 0 && i <= max) out.add(i);
                    }
                } else {
                    int v = Integer.parseInt(t);
                    if (v < 0 || v > max) {
                        r.warn(path, t, "Slot outside the menu", "0-" + max, "slot ignored");
                    } else {
                        out.add(v);
                    }
                }
            } catch (NumberFormatException e) {
                r.warn(path, t, "Invalid slot", "a number or a range like 10-16", "slot ignored");
            }
        }
        return out;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public int rows() {
        return rows;
    }

    public ItemSpec item(String key) {
        return items.get(key);
    }

    public Map<String, ItemSpec> items() {
        return items;
    }

    public List<Integer> contentSlots() {
        return contentSlots;
    }

    public ItemSpec filler() {
        return filler;
    }
}
