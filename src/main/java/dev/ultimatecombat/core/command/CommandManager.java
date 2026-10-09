package dev.ultimatecombat.core.command;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Registers {@code /ultimatecombat} (from plugin.yml) and every stand-alone command defined in
 * {@code commands.yml}. Stand-alone commands are registered at runtime so administrators can
 * rename them, add aliases or disable them, and they never replace another plugin's command
 * unless {@code override-existing} is enabled for that command.
 */
public final class CommandManager {

    private final UltimateCombatPlugin plugin;
    private final UCCommand root;
    private final Map<String, UCCommand> standalone = new LinkedHashMap<>();
    private final List<DynamicCommand> registered = new ArrayList<>();

    public CommandManager(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
        this.root = new UCCommand("ultimatecombat", "ultimatecombat.command", false, "uc") {
            @Override
            protected void run(CommandContext ctx) {
                sendHelp(ctx, ctx.label());
            }
        };
    }

    public UCCommand root() {
        return root;
    }

    /**
     * Declares a stand-alone command (for example {@code togglepvp}); whether and under which name
     * it is registered is decided by commands.yml.
     */
    public void standalone(String id, UCCommand command) {
        standalone.put(id, command);
    }

    public void registerAll() {
        PluginCommand main = plugin.getCommand("ultimatecombat");
        if (main != null) {
            main.setExecutor((sender, command, label, args) -> {
                dispatch(root, sender, label, args);
                return true;
            });
            main.setTabCompleter((sender, command, label, args) -> root.complete(new CommandContext(plugin, sender, label, args)));
        }
        registerStandalone();
    }

    private void dispatch(UCCommand command, CommandSender sender, String label, String[] args) {
        try {
            command.execute(new CommandContext(plugin, sender, label, args));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Error while executing /" + label, e);
            plugin.messages().send(sender, "general.internal-error");
        }
    }

    /**
     * (Re)registers stand-alone commands according to commands.yml.
     */
    public void registerStandalone() {
        CommandMap map = commandMap();
        if (map == null) {
            plugin.getLogger().warning("Command map unavailable; stand-alone commands are only reachable via /uc");
            return;
        }
        unregisterStandalone(map);
        ConfigReader cfg = plugin.configs().file("commands.yml").reader();
        boolean anyChanged = false;
        for (Map.Entry<String, UCCommand> entry : standalone.entrySet()) {
            String id = entry.getKey();
            ConfigReader c = cfg.section("commands." + id);
            if (!c.bool("enabled", true)) {
                continue;
            }
            String name = c.string("name", id).toLowerCase(Locale.ROOT).trim();
            List<String> aliases = new ArrayList<>();
            for (String a : c.stringList("aliases")) {
                aliases.add(a.toLowerCase(Locale.ROOT).trim());
            }
            boolean override = c.bool("override-existing", false);
            List<String> labels = new ArrayList<>();
            labels.add(name);
            labels.addAll(aliases);
            List<String> usable = new ArrayList<>();
            for (String label : labels) {
                Command existing = map.getCommand(label);
                if (existing != null && !override && !isOurs(existing)) {
                    String owner = existing instanceof PluginIdentifiableCommand pic ? pic.getPlugin().getName() : "server";
                    plugin.getLogger().info("Not registering /" + label + " because " + owner + " already provides it "
                            + "(set commands." + id + ".override-existing: true in commands.yml to replace it). "
                            + "It stays available as /ultimatecombat:" + label + ".");
                    continue;
                }
                usable.add(label);
            }
            DynamicCommand command = new DynamicCommand(name, aliases, entry.getValue());
            if (override) {
                for (String label : usable) {
                    Command existing = map.getCommand(label);
                    if (existing != null && !isOurs(existing)) {
                        knownCommands(map).remove(label);
                    }
                }
            }
            // The command map never replaces labels owned by other plugins; the namespaced
            // "ultimatecombat:<label>" form is always registered as a fallback.
            map.register(plugin.getName().toLowerCase(Locale.ROOT), command);
            registered.add(command);
            anyChanged = true;
        }
        if (anyChanged) {
            syncCommands();
        }
    }

    private boolean isOurs(Command command) {
        return command instanceof DynamicCommand || command instanceof PluginIdentifiableCommand pic && pic.getPlugin() == plugin;
    }

    public void unregisterAll() {
        CommandMap map = commandMap();
        if (map != null) {
            unregisterStandalone(map);
        }
    }

    private void unregisterStandalone(CommandMap map) {
        if (registered.isEmpty()) {
            return;
        }
        Map<String, Command> known = knownCommands(map);
        for (DynamicCommand command : registered) {
            command.unregister(map);
            known.values().removeIf(c -> c == command);
        }
        registered.clear();
    }

    private void syncCommands() {
        try {
            Method sync = Bukkit.getServer().getClass().getMethod("syncCommands");
            sync.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not available on this platform; clients refresh on next login
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().runEntity(p, p::updateCommands);
        }
    }

    private static CommandMap commandMap() {
        try {
            return Bukkit.getCommandMap();
        } catch (NoSuchMethodError e) {
            try {
                Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
                return (CommandMap) m.invoke(Bukkit.getServer());
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(CommandMap map) {
        try {
            return map.getKnownCommands();
        } catch (NoSuchMethodError e) {
            try {
                Field f = findField(map.getClass(), "knownCommands");
                f.setAccessible(true);
                return (Map<String, Command>) f.get(map);
            } catch (ReflectiveOperationException ex) {
                return Collections.emptyMap();
            }
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * Bukkit command delegating to a {@link UCCommand}.
     */
    private final class DynamicCommand extends Command implements PluginIdentifiableCommand {

        private final UCCommand delegate;

        DynamicCommand(String name, List<String> aliases, UCCommand delegate) {
            super(name, "", "/" + name, aliases);
            this.delegate = delegate;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            if (!plugin.isEnabled()) {
                return false;
            }
            dispatch(delegate, sender, label, args);
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
            return delegate.complete(new CommandContext(plugin, sender, alias, args));
        }

        @Override
        public @NotNull Plugin getPlugin() {
            return plugin;
        }
    }
}
