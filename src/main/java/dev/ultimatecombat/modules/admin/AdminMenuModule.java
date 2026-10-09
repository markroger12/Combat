package dev.ultimatecombat.modules.admin;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.commands.CoreCommands;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.config.ConfigService;
import dev.ultimatecombat.core.gui.Menu;
import dev.ultimatecombat.core.gui.MenuTemplate;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.Numbers;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-game configuration editor ({@code /uc editor}). It browses every YAML file and edits values
 * through exactly the same validated path as {@code /uc config set} (backup, write, reload, roll
 * back invalid values), so the GUI can never bypass validation.
 *
 * <p>Layouts: menus.yml ({@code editor-files}, {@code editor-section}); options: menus.yml
 * {@code editor}.</p>
 */
public final class AdminMenuModule extends Module<AdminMenuModule.Settings> {

    public record Settings() {
    }

    private record Options(Set<String> hiddenFiles, int documentationLines, double decimalStep) {
    }

    private record Entry(String key, String path, Object value, boolean section) {
    }

    public AdminMenuModule(UltimateCombatPlugin plugin) {
        super(plugin, "admin-menu", null);
    }

    @Override
    protected Settings parse(ConfigReader reader) {
        return new Settings();
    }

    private Options options() {
        ConfigReader r = plugin.configs().file("menus.yml").reader();
        return new Options(r.lowerCaseSet("editor.hidden-files"), r.integer("editor.documentation-lines", 6, 0, 30),
                r.decimal("editor.decimal-step", 0.1, 0.0001, 1000));
    }

    @Override
    public void registerCommands() {
        plugin.commands().root().child(new SimpleCommand("editor", "editor [file]", "ultimatecombat.admin.editor", true, ctx -> {
            if (!guard(ctx)) {
                return;
            }
            if (ctx.size() > 0) {
                ConfigFile file = plugin.configs().find(ctx.arg(0));
                if (file == null) {
                    ctx.reply("admin-menu.unknown-file", "file", ctx.arg(0));
                    return;
                }
                openSection(ctx.player(), file, "", 0);
                return;
            }
            openFiles(ctx.player(), 0);
        }, ctx -> {
            List<String> out = new ArrayList<>();
            for (ConfigFile f : plugin.configs().files()) {
                out.add(f.name());
            }
            return ctx.size() == 1 ? out : List.of();
        }));
    }

    // ------------------------------------------------------------------------------ file list

    private void openFiles(Player viewer, int page) {
        if (!isEnabled() || !viewer.hasPermission("ultimatecombat.admin.editor")) {
            return;
        }
        Options o = options();
        List<ConfigFile> files = new ArrayList<>();
        for (ConfigFile f : plugin.configs().files()) {
            if (!o.hiddenFiles().contains(f.name().toLowerCase(Locale.ROOT))) {
                files.add(f);
            }
        }
        files.sort(Comparator.comparing(ConfigFile::name));
        MenuTemplate template = plugin.menus().template("editor-files");
        Map<String, Menu.ClickHandler> handlers = new HashMap<>();
        handlers.put("close", (p, c) -> plugin.scheduler().runEntity(p, p::closeInventory));
        handlers.put("reload", (p, c) -> plugin.scheduler().runGlobal(() -> {
            plugin.reload("all");
            plugin.messages().send(p, "admin-menu.reloaded");
            plugin.scheduler().runEntity(p, () -> openFiles(p, page));
        }));
        handlers.put("validate", (p, c) -> {
            p.closeInventory();
            p.performCommand("ultimatecombat validate");
        });
        Menu menu = plugin.menus().build(viewer, "editor-files", handlers, "files", files.size(),
                "issues", plugin.configs().issues().size());
        plugin.menus().paginate(menu, viewer, template, files, page, f -> fileItem(viewer, f),
                (f, click) -> {
                    if (click.isShiftClick()) {
                        toggleModule(viewer, f, page);
                    } else {
                        openSection(viewer, f, "", 0);
                    }
                }, p -> openFiles(viewer, p));
        menu.open(viewer);
    }

    private Module<?> moduleOf(ConfigFile file) {
        for (Module<?> m : plugin.modules().all()) {
            if (file.name().equals(m.configName())) {
                return m;
            }
        }
        return null;
    }

    private ItemStack fileItem(Player viewer, ConfigFile f) {
        Module<?> module = moduleOf(f);
        long issues = f.issues().size();
        Material material = f.parseFailed() ? Material.BARRIER : issues > 0 ? Material.WRITABLE_BOOK
                : module == null ? Material.BOOK : module.isEnabled() ? Material.ENCHANTED_BOOK : Material.BOOK;
        String status = module == null ? plugin.messages().text(viewer, "admin-menu.core-file")
                : plugin.messages().text(viewer, module.isEnabled() ? "admin-menu.module-on" : "admin-menu.module-off", "module", module.id());
        return new ItemBuilder(material)
                .name(plugin.messages().text(viewer, "admin-menu.file-name", "file", f.name()))
                .lore(plugin.messages().lines(viewer, module == null ? "admin-menu.file-lore" : "admin-menu.module-file-lore",
                        "issues", issues, "status", status, "module", module == null ? "" : module.id()))
                .build();
    }

    private void toggleModule(Player viewer, ConfigFile file, int page) {
        Module<?> module = moduleOf(file);
        if (module == null) {
            return;
        }
        ConfigService.Reference ref = plugin.configs().resolve("features.yml:modules." + module.id());
        if (ref == null) {
            return;
        }
        boolean target = !module.isEnabled();
        edit(viewer, ref, target, () -> openFiles(viewer, page));
    }

    // ------------------------------------------------------------------------------ sections

    private List<Entry> entries(ConfigFile file, String path) {
        List<Entry> out = new ArrayList<>();
        for (String key : file.childKeys(path)) {
            String child = path.isEmpty() ? key : path + "." + key;
            if (child.equals("config-version")) {
                continue;
            }
            Object value = file.rawValue(child);
            boolean section = file.isSection(child);
            if (value == null && !section) {
                continue;
            }
            out.add(new Entry(key, child, value, section));
        }
        return out;
    }

    private void openSection(Player viewer, ConfigFile file, String path, int page) {
        if (!isEnabled() || !viewer.hasPermission("ultimatecombat.admin.editor")) {
            return;
        }
        MenuTemplate template = plugin.menus().template("editor-section");
        Map<String, Menu.ClickHandler> handlers = new HashMap<>();
        handlers.put("close", (p, c) -> plugin.scheduler().runEntity(p, p::closeInventory));
        handlers.put("back", (p, c) -> plugin.scheduler().runEntity(p, () -> {
            if (path.isEmpty()) {
                openFiles(p, 0);
            } else {
                int dot = path.lastIndexOf('.');
                openSection(p, file, dot < 0 ? "" : path.substring(0, dot), 0);
            }
        }));
        String shown = path.isEmpty() ? "/" : path;
        Menu menu = plugin.menus().build(viewer, "editor-section", handlers, "file", file.name(), "path", shown);
        List<Entry> entries = entries(file, path);
        Options o = options();
        plugin.menus().paginate(menu, viewer, template, entries, page, e -> entryItem(viewer, file, e, o),
                (e, click) -> onEntryClick(viewer, file, path, page, e, click, o), p -> openSection(viewer, file, path, p));
        menu.open(viewer);
    }

    private ItemStack entryItem(Player viewer, ConfigFile file, Entry e, Options o) {
        Material material;
        String hint;
        String value;
        if (e.section()) {
            material = Material.CHEST;
            hint = "admin-menu.hint-section";
            Object raw = e.value();
            value = raw instanceof ConfigurationSection s ? s.getKeys(false).size() + "" : "0";
        } else if (e.value() instanceof Boolean b) {
            material = b ? Material.LIME_DYE : Material.GRAY_DYE;
            hint = "admin-menu.hint-boolean";
            value = plugin.messages().bool(b);
        } else if (e.value() instanceof Number n) {
            material = n instanceof Double || n instanceof Float ? Material.GOLD_INGOT : Material.GOLD_NUGGET;
            hint = "admin-menu.hint-number";
            value = n.toString();
        } else if (e.value() instanceof List<?> list) {
            material = Material.PAPER;
            hint = "admin-menu.hint-list";
            value = list.isEmpty() ? "[]" : list.size() + "";
        } else {
            material = Material.NAME_TAG;
            hint = "admin-menu.hint-text";
            value = String.valueOf(e.value());
        }
        List<String> lore = new ArrayList<>(plugin.messages().lines(viewer, "admin-menu.entry-value", "value", shorten(value),
                "source", file.isSetByUser(e.path()) ? plugin.messages().text(viewer, "admin-menu.source-file")
                        : plugin.messages().text(viewer, "admin-menu.source-default")));
        if (e.value() instanceof List<?> list) {
            int shown = 0;
            for (Object item : list) {
                if (shown++ >= 8) {
                    lore.add(plugin.messages().text(viewer, "admin-menu.list-more", "count", list.size() - 8));
                    break;
                }
                lore.add(plugin.messages().text(viewer, "admin-menu.list-entry", "entry", shorten(String.valueOf(item))));
            }
        }
        int docs = 0;
        for (String line : file.comments(e.path())) {
            String t = line.trim();
            if (t.isEmpty() || t.contains("@user-map") || t.startsWith("====")) {
                continue;
            }
            if (docs++ >= o.documentationLines()) {
                break;
            }
            lore.add(plugin.messages().text(viewer, "admin-menu.doc-line", "line", shorten(t)));
        }
        lore.addAll(plugin.messages().lines(viewer, hint));
        return new ItemBuilder(material)
                .name(plugin.messages().text(viewer, "admin-menu.entry-name", "key", e.key()))
                .lore(lore)
                .build();
    }

    private static String shorten(String text) {
        String t = text.replace('\n', ' ');
        return t.length() > 60 ? t.substring(0, 57) + "..." : t;
    }

    private void onEntryClick(Player viewer, ConfigFile file, String path, int page, Entry e, ClickType click, Options o) {
        if (e.section()) {
            openSection(viewer, file, e.path(), 0);
            return;
        }
        if (!viewer.hasPermission("ultimatecombat.config.set")) {
            plugin.messages().send(viewer, "general.no-permission");
            return;
        }
        ConfigService.Reference ref = new ConfigService.Reference(file, e.path());
        Runnable reopen = () -> openSection(viewer, file, path, page);
        Object value = e.value();
        if (value instanceof Boolean b) {
            edit(viewer, ref, !b, reopen);
        } else if (value instanceof Number n && !click.isShiftClick()) {
            boolean decimal = n instanceof Double || n instanceof Float;
            int sign = click.isRightClick() ? -1 : 1;
            Object next = decimal ? (Object) Numbers.round(n.doubleValue() + sign * o.decimalStep(), 4) : (Object) (n.longValue() + sign);
            if (!decimal && next instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                next = l.intValue();
            }
            edit(viewer, ref, next, reopen);
        } else if (value instanceof List<?> list && click == ClickType.SHIFT_RIGHT) {
            edit(viewer, ref, new ArrayList<String>(), reopen);
        } else if (value instanceof List<?> list && click.isRightClick()) {
            plugin.prompts().ask(viewer, "admin-menu.prompt-list-add", input -> {
                List<Object> copy = new ArrayList<>(list);
                copy.add(input);
                edit(viewer, ref, copy, reopen);
            }, reopen, "path", e.path());
        } else {
            String current = value instanceof List<?> list ? String.join(", ", list.stream().map(String::valueOf).toList())
                    : String.valueOf(value);
            plugin.prompts().ask(viewer, value instanceof List<?> ? "admin-menu.prompt-list" : "admin-menu.prompt-value",
                    input -> editText(viewer, ref, input, reopen), reopen, "path", e.path(), "value", current);
        }
    }

    private void edit(Player viewer, ConfigService.Reference ref, Object value, Runnable reopen) {
        plugin.scheduler().runGlobal(() -> {
            CoreCommands.setValue(plugin, new CommandContext(plugin, viewer, "ultimatecombat", new String[0]), ref, value);
            plugin.scheduler().runEntity(viewer, reopen);
        });
    }

    private void editText(Player viewer, ConfigService.Reference ref, String text, Runnable reopen) {
        plugin.scheduler().runGlobal(() -> {
            CoreCommands.set(plugin, new CommandContext(plugin, viewer, "ultimatecombat", new String[0]), ref, text);
            plugin.scheduler().runEntity(viewer, reopen);
        });
    }
}
