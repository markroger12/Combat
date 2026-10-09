package dev.ultimatecombat.core.gui;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Builds menus from {@code menus.yml} templates.
 *
 * <p>Item actions (configurable per item in menus.yml, executed in order on click):</p>
 * <ul>
 *     <li>{@code [close]} closes the menu</li>
 *     <li>{@code [console] <command>} runs a console command ({player} = clicker)</li>
 *     <li>{@code [player] <command>} makes the clicker run a command (their permissions apply)</li>
 *     <li>{@code [message] <text>} sends a message</li>
 *     <li>{@code [sound] <preset>} plays a sound preset from sounds.yml</li>
 * </ul>
 */
public final class MenuService {

    private final UltimateCombatPlugin plugin;
    private volatile Map<String, MenuTemplate> templates = Map.of();

    public MenuService(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ConfigReader r = plugin.configs().file("menus.yml").reader();
        Map<String, MenuTemplate> map = new HashMap<>();
        for (String id : r.keys("menus")) {
            map.put(id, MenuTemplate.read(id, r.section("menus." + id)));
        }
        templates = map;
    }

    public MenuTemplate template(String id) {
        MenuTemplate t = templates.get(id);
        if (t == null) {
            t = new MenuTemplate(id, id, 6, null, Map.of(), List.of());
        }
        return t;
    }

    /**
     * Creates a menu with filler and every configured item. {@code handlers} maps item keys to
     * module behaviour; configured actions run in addition.
     */
    public Menu build(Player viewer, String id, Map<String, Menu.ClickHandler> handlers, Object... placeholders) {
        MenuTemplate t = template(id);
        Menu menu = new Menu(t.rows(), plugin.messages().apply(viewer, t.title(), placeholders));
        MenuTemplate.ItemSpec filler = t.filler();
        if (filler != null) {
            ItemStack fill = filler.build(plugin.messages(), viewer, placeholders);
            List<Integer> slots = filler.slots();
            if (slots.isEmpty()) {
                for (int i = 0; i < menu.size(); i++) {
                    menu.set(i, fill);
                }
            } else {
                for (int slot : slots) {
                    menu.set(slot, fill);
                }
            }
        }
        for (MenuTemplate.ItemSpec spec : t.items().values()) {
            if (!spec.enabled()) {
                continue;
            }
            if (!spec.permission().isEmpty() && !viewer.hasPermission(spec.permission())) {
                continue;
            }
            Menu.ClickHandler moduleHandler = handlers == null ? null : handlers.get(spec.key());
            ItemStack item = spec.build(plugin.messages(), viewer, placeholders);
            for (int slot : spec.slots()) {
                menu.set(slot, item, (p, click) -> {
                    if (!spec.sound().isEmpty()) {
                        plugin.effects().playSound(p, spec.sound());
                    }
                    if (moduleHandler != null) {
                        moduleHandler.onClick(p, click);
                    }
                    runActions(p, spec.actions());
                });
            }
        }
        return menu;
    }

    public void runActions(Player player, List<String> actions) {
        for (String action : actions) {
            String a = action.trim();
            String lower = a.toLowerCase();
            if (lower.equals("[close]")) {
                plugin.scheduler().runEntity(player, player::closeInventory);
            } else if (lower.startsWith("[console]")) {
                String cmd = a.substring(9).trim().replace("{player}", player.getName());
                plugin.scheduler().runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            } else if (lower.startsWith("[player]")) {
                String cmd = a.substring(8).trim().replace("{player}", player.getName());
                plugin.scheduler().runEntity(player, () -> player.performCommand(cmd));
            } else if (lower.startsWith("[message]")) {
                player.sendMessage(plugin.messages().apply(player, a.substring(9).trim(), "player", player.getName()));
            } else if (lower.startsWith("[sound]")) {
                plugin.effects().playSound(player, a.substring(7).trim());
            }
        }
    }

    /**
     * Fills the template's content slots with one page of entries and wires the page buttons
     * ({@code previous-page} / {@code next-page} item keys).
     *
     * @return number of pages
     */
    public <T> int paginate(Menu menu, Player viewer, MenuTemplate template, List<T> entries, int page,
                            Function<T, ItemStack> render, BiConsumer<T, ClickType> onClick,
                            java.util.function.IntConsumer openPage) {
        List<Integer> slots = template.contentSlots();
        if (slots.isEmpty()) {
            return 1;
        }
        int perPage = slots.size();
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < perPage; i++) {
            int index = current * perPage + i;
            int slot = slots.get(i);
            if (index >= entries.size()) {
                menu.set(slot, null);
                continue;
            }
            T entry = entries.get(index);
            menu.set(slot, render.apply(entry), onClick == null ? null : (p, click) -> onClick.accept(entry, click));
        }
        MenuTemplate.ItemSpec prev = template.item("previous-page");
        if (prev != null && prev.enabled()) {
            for (int slot : prev.slots()) {
                if (current > 0) {
                    menu.set(slot, prev.build(plugin.messages(), viewer, "page", current, "pages", pages),
                            (p, c) -> openPage.accept(current - 1));
                }
            }
        }
        MenuTemplate.ItemSpec next = template.item("next-page");
        if (next != null && next.enabled()) {
            for (int slot : next.slots()) {
                if (current < pages - 1) {
                    menu.set(slot, next.build(plugin.messages(), viewer, "page", current + 2, "pages", pages),
                            (p, c) -> openPage.accept(current + 1));
                }
            }
        }
        return pages;
    }
}
