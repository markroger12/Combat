package dev.ultimatecombat.commands;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigService;
import dev.ultimatecombat.core.config.ValidationIssue;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.Platform;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Administrative sub commands of /ultimatecombat that are not tied to one feature module.
 */
public final class CoreCommands {

    private CoreCommands() {
    }

    public static void register(UltimateCombatPlugin plugin) {
        UCCommand root = plugin.commands().root();
        root.child(new SimpleCommand("help", "help", "ultimatecombat.command", false, ctx -> root.sendHelp(ctx, "uc")));
        root.child(new SimpleCommand("reload", "reload [all|<module>|<file>]", "ultimatecombat.reload", false,
                ctx -> reload(plugin, ctx), ctx -> reloadTargets(plugin)));
        root.child(new SimpleCommand("validate", "validate", "ultimatecombat.validate", false, ctx -> validate(plugin, ctx)));
        root.child(configCommand(plugin));
        root.child(new SimpleCommand("migrate", "migrate [preview]", "ultimatecombat.migrate", false,
                ctx -> migrate(plugin, ctx), ctx -> List.of("preview")));
        root.child(new SimpleCommand("backup", "backup", "ultimatecombat.backup", false, ctx -> {
            int count = plugin.configs().backupAll();
            ctx.reply("admin.backup.done", "count", count);
        }));
        root.child(new SimpleCommand("status", "status", "ultimatecombat.status", false, ctx -> status(plugin, ctx)));
        root.child(new SimpleCommand("version", "version", "ultimatecombat.command", false,
                ctx -> ctx.reply("admin.version", "version", plugin.getDescription().getVersion(), "platform", plugin.platform(),
                        "minecraft", Platform.minecraftVersion())));
        root.child(new SimpleCommand("debug", "debug [toggle|counters]", "ultimatecombat.debug", false,
                ctx -> debug(plugin, ctx), ctx -> List.of("toggle", "counters")));
        root.child(new SimpleCommand("integrations", "integrations", "ultimatecombat.integrations", false,
                ctx -> integrations(plugin, ctx)));
    }

    private static List<String> reloadTargets(UltimateCombatPlugin plugin) {
        List<String> out = new ArrayList<>();
        out.add("all");
        out.add("messages");
        for (Module<?> m : plugin.modules().all()) {
            out.add(m.id());
        }
        for (ConfigFile f : plugin.configs().files()) {
            if (!f.name().contains("/")) {
                out.add(f.name());
            }
        }
        return out;
    }

    private static void reload(UltimateCombatPlugin plugin, CommandContext ctx) {
        String target = ctx.argOr(0, "all");
        long start = System.currentTimeMillis();
        int before = plugin.configs().issues().size();
        List<String> reloaded = plugin.reload(target);
        if (reloaded.isEmpty()) {
            ctx.reply("admin.reload.unknown-target", "target", target);
            return;
        }
        int issues = plugin.configs().issues().size();
        ctx.reply("admin.reload.done", "target", String.join(", ", reloaded), "time", System.currentTimeMillis() - start);
        if (issues > 0) {
            ctx.reply("admin.reload.issues", "count", issues);
        }
        if (plugin.storageChangedSinceStartup()) {
            ctx.reply("admin.reload.restart-required", "file", "storage.yml");
        }
        plugin.debug().log("config", "Reload " + target + ": issues before=" + before + " after=" + issues);
    }

    private static void validate(UltimateCombatPlugin plugin, CommandContext ctx) {
        // Re-read every file so the report reflects the current state on disk without applying it.
        for (ConfigFile f : plugin.configs().files()) {
            f.load();
        }
        plugin.reload("all");
        List<ValidationIssue> issues = plugin.configs().issues();
        int missing = 0;
        for (ConfigFile f : plugin.configs().files()) {
            missing += f.missingKeys().size();
        }
        if (issues.isEmpty()) {
            ctx.reply("admin.validate.ok", "files", plugin.configs().files().size());
        } else {
            ctx.reply("admin.validate.header", "count", issues.size());
            int shown = 0;
            for (ValidationIssue issue : issues) {
                if (shown++ >= 30) {
                    ctx.reply("admin.validate.more", "count", issues.size() - 30);
                    break;
                }
                ctx.reply(issue.severity() == ValidationIssue.Severity.ERROR ? "admin.validate.error" : "admin.validate.entry",
                        "issue", issue.describe());
            }
        }
        if (missing > 0) {
            ctx.reply("admin.validate.missing", "count", missing);
        }
    }

    private static UCCommand configCommand(UltimateCombatPlugin plugin) {
        UCCommand config = new UCCommand("config", "ultimatecombat.config", false) {
            @Override
            public String usage() {
                return "config <explain|get|set> <file:path> [value]";
            }
        };
        config.child(new SimpleCommand("explain", "explain <file:path>", "ultimatecombat.config", false, ctx -> {
            ConfigService.Reference ref = reference(plugin, ctx);
            if (ref == null) {
                return;
            }
            List<String> comments = ref.file().comments(ref.path());
            ctx.reply("admin.config.explain-header", "file", ref.file().name(), "path", ref.path());
            if (comments.isEmpty()) {
                ctx.reply("admin.config.no-documentation");
            }
            for (String line : comments) {
                ctx.reply("admin.config.explain-line", "line", line.isEmpty() ? " " : line.trim());
            }
            ctx.reply("admin.config.current", "value", describe(ref.file().rawValue(ref.path())));
        }, ctx -> referenceCompletions(plugin, ctx)));
        config.child(new SimpleCommand("get", "get <file:path>", "ultimatecombat.config", false, ctx -> {
            ConfigService.Reference ref = reference(plugin, ctx);
            if (ref == null) {
                return;
            }
            ctx.reply("admin.config.value", "file", ref.file().name(), "path", ref.path(),
                    "value", describe(ref.file().rawValue(ref.path())),
                    "source", ref.file().isSetByUser(ref.path()) ? "file" : "default");
        }, ctx -> referenceCompletions(plugin, ctx)));
        config.child(new SimpleCommand("set", "set <file:path> <value>", "ultimatecombat.config.set", false, ctx -> {
            if (ctx.size() < 2) {
                ctx.reply("general.usage", "usage", "/uc config set <file:path> <value>");
                return;
            }
            ConfigService.Reference ref = reference(plugin, ctx);
            if (ref == null) {
                return;
            }
            set(plugin, ctx, ref, ctx.joinFrom(1));
        }, ctx -> referenceCompletions(plugin, ctx)));
        return config;
    }

    /**
     * Writes a setting, reloads the owning module and rolls back if the new value is invalid.
     * Shared by the command and the GUI editor.
     */
    public static boolean set(UltimateCombatPlugin plugin, CommandContext ctx, ConfigService.Reference ref, String text) {
        Object current = ref.file().rawValue(ref.path());
        if (current == null) {
            ctx.reply("admin.config.unknown-path", "path", ref.path(), "file", ref.file().name());
            return false;
        }
        if (current instanceof ConfigurationSection) {
            ctx.reply("admin.config.is-section", "path", ref.path());
            return false;
        }
        Object value = ConfigService.coerce(current, text);
        if (value == null) {
            ctx.reply("admin.config.wrong-type", "value", text, "type", typeName(current));
            return false;
        }
        Object previous = ref.file().isSetByUser(ref.path()) ? current : null;
        plugin.configs().newBackupSession();
        if (!ref.file().write(ref.path(), value)) {
            ctx.reply("admin.config.write-failed", "file", ref.file().name());
            return false;
        }
        plugin.reload(ref.file().name());
        boolean invalid = ref.file().issues().stream().anyMatch(i -> i.path().equals(ref.path()));
        if (invalid) {
            ValidationIssue issue = ref.file().issues().stream().filter(i -> i.path().equals(ref.path())).findFirst().orElseThrow();
            ref.file().write(ref.path(), previous);
            plugin.reload(ref.file().name());
            ctx.reply("admin.config.rejected", "reason", issue.describe());
            return false;
        }
        ctx.reply("admin.config.updated", "file", ref.file().name(), "path", ref.path(), "value", describe(value));
        if (ref.file().name().equals("storage.yml")) {
            ctx.reply("admin.reload.restart-required", "file", "storage.yml");
        }
        return true;
    }

    private static String typeName(Object o) {
        if (o instanceof Boolean) return "true/false";
        if (o instanceof Integer || o instanceof Long) return "whole number";
        if (o instanceof Double || o instanceof Float) return "number";
        if (o instanceof List<?>) return "comma separated list";
        return "text";
    }

    private static String describe(Object value) {
        if (value == null) {
            return "<not set>";
        }
        if (value instanceof ConfigurationSection s) {
            return "<section: " + String.join(", ", s.getKeys(false)) + ">";
        }
        return value.toString();
    }

    private static ConfigService.Reference reference(UltimateCombatPlugin plugin, CommandContext ctx) {
        String raw = ctx.arg(0);
        if (raw == null) {
            ctx.reply("general.usage", "usage", "/uc config <explain|get|set> <file:path>");
            return null;
        }
        ConfigService.Reference ref = plugin.configs().resolve(raw);
        if (ref == null) {
            ctx.reply("admin.config.bad-reference", "reference", raw);
            return null;
        }
        return ref;
    }

    private static List<String> referenceCompletions(UltimateCombatPlugin plugin, CommandContext ctx) {
        if (ctx.size() > 1) {
            return List.of();
        }
        String typed = ctx.argOr(0, "");
        List<String> out = new ArrayList<>();
        int idx = typed.indexOf(':');
        if (idx < 0) {
            for (ConfigFile f : plugin.configs().files()) {
                out.add(f.name() + ":");
            }
            return out;
        }
        ConfigFile file = plugin.configs().find(typed.substring(0, idx));
        if (file == null) {
            return out;
        }
        String prefix = typed.substring(0, idx + 1);
        String path = typed.substring(idx + 1);
        int depth = path.isEmpty() ? 0 : path.split("\\.", -1).length - 1;
        java.util.Set<String> keys = new java.util.TreeSet<>();
        collectKeys(file, keys);
        for (String key : keys) {
            if (key.startsWith(path) && key.split("\\.").length - 1 <= depth + 1) {
                out.add(prefix + key);
            }
        }
        return out.size() > 100 ? out.subList(0, 100) : out;
    }

    private static void collectKeys(ConfigFile file, java.util.Set<String> keys) {
        Object root = file.rawValue("");
        if (root instanceof ConfigurationSection s) {
            keys.addAll(s.getKeys(true));
        }
    }

    private static void migrate(UltimateCombatPlugin plugin, CommandContext ctx) {
        boolean preview = "preview".equalsIgnoreCase(ctx.arg(0));
        plugin.configs().newBackupSession();
        List<String> structural = plugin.configs().runMigrations(!preview);
        int total = 0;
        for (ConfigFile f : plugin.configs().files()) {
            int missing = f.missingKeys().size();
            if (missing == 0) {
                continue;
            }
            if (preview) {
                ctx.reply("admin.migrate.preview-file", "file", f.name(), "count", missing);
            } else {
                int added = f.addMissingKeys();
                if (added > 0) {
                    total += added;
                    ctx.reply("admin.migrate.file", "file", f.name(), "count", added);
                } else if (added < 0) {
                    ctx.reply("admin.migrate.failed", "file", f.name());
                }
            }
        }
        for (String s : structural) {
            ctx.reply("admin.migrate.structural", "change", s);
        }
        if (!preview) {
            plugin.reload("all");
            ctx.reply("admin.migrate.done", "count", total);
        }
    }

    private static void status(UltimateCombatPlugin plugin, CommandContext ctx) {
        ctx.reply("admin.status.header", "version", plugin.getDescription().getVersion());
        ctx.reply("admin.status.platform", "platform", plugin.platform(), "minecraft", Platform.minecraftVersion(),
                "java", System.getProperty("java.version"));
        ctx.reply("admin.status.storage", "type", plugin.storageReady() ? plugin.database().type() : "UNAVAILABLE",
                "group", plugin.storageReady() ? plugin.database().group() : "-",
                "redis", plugin.messages().bool(plugin.sync() != null && plugin.sync().active()));
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (Module<?> m : plugin.modules().all()) {
            (m.isEnabled() ? on : off).add(m.id());
        }
        ctx.reply("admin.status.modules-on", "count", on.size(), "list", String.join(", ", on));
        ctx.reply("admin.status.modules-off", "count", off.size(), "list", off.isEmpty() ? "-" : String.join(", ", off));
        ctx.reply("admin.status.issues", "count", plugin.configs().issues().size());
        ctx.reply("admin.status.players", "count", Bukkit.getOnlinePlayers().size(),
                "profiles", plugin.players().onlineProfiles().size());
    }

    private static void debug(UltimateCombatPlugin plugin, CommandContext ctx) {
        String sub = ctx.argOr(0, "toggle").toLowerCase(Locale.ROOT);
        if (sub.equals("counters")) {
            Map<String, AtomicLong> counters = plugin.debug().counters();
            ctx.reply("admin.debug.counters-header", "count", counters.size());
            counters.forEach((k, v) -> ctx.reply("admin.debug.counter", "name", k, "value", v.get()));
            return;
        }
        if (!ctx.isPlayer()) {
            ctx.reply("admin.debug.console-hint");
            return;
        }
        boolean on = plugin.debug().toggleListener(ctx.player().getUniqueId());
        ctx.reply(on ? "admin.debug.enabled" : "admin.debug.disabled");
    }

    private static void integrations(UltimateCombatPlugin plugin, CommandContext ctx) {
        ctx.reply("admin.integrations.header");
        for (IntegrationManager.State state : plugin.integrations().states()) {
            String key = switch (state.status()) {
                case ACTIVE -> "admin.integrations.active";
                case DISABLED_IN_CONFIG -> "admin.integrations.disabled";
                case NOT_INSTALLED -> "admin.integrations.missing";
                case FAILED -> "admin.integrations.failed";
            };
            ctx.reply(key, "name", state.definition().pluginName(), "id", state.definition().id(), "detail", state.detail());
        }
    }
}
