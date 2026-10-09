package dev.ultimatecombat.core.command;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A command or sub command. Groups dispatch to children; leaves implement {@link #run}.
 */
public abstract class UCCommand {

    private final String name;
    private final List<String> aliases;
    private final String permission;
    private final boolean playerOnly;
    private final Map<String, UCCommand> children = new LinkedHashMap<>();
    private final Map<String, UCCommand> lookup = new LinkedHashMap<>();

    protected UCCommand(String name, String permission, boolean playerOnly, String... aliases) {
        this.name = name.toLowerCase(Locale.ROOT);
        this.permission = permission;
        this.playerOnly = playerOnly;
        this.aliases = List.of(aliases);
    }

    public String name() {
        return name;
    }

    public List<String> aliases() {
        return aliases;
    }

    public String permission() {
        return permission;
    }

    /**
     * Language key of the one-line description shown in help.
     */
    public String descriptionKey() {
        return "help.descriptions." + name;
    }

    /**
     * Usage line shown on wrong arguments (not translated: syntax only).
     */
    public String usage() {
        return name;
    }

    public UCCommand child(UCCommand command) {
        children.put(command.name(), command);
        lookup.put(command.name(), command);
        for (String alias : command.aliases()) {
            lookup.put(alias.toLowerCase(Locale.ROOT), command);
        }
        return this;
    }

    public void removeChild(String childName) {
        UCCommand removed = children.remove(childName);
        if (removed != null) {
            lookup.values().removeIf(c -> c == removed);
        }
    }

    public Collection<UCCommand> children() {
        return children.values();
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public final void execute(CommandContext ctx) {
        if (!ctx.has(permission)) {
            ctx.reply("general.no-permission");
            return;
        }
        if (hasChildren() && ctx.size() > 0) {
            UCCommand sub = lookup.get(ctx.arg(0).toLowerCase(Locale.ROOT));
            if (sub != null) {
                sub.execute(ctx.shift(sub.name()));
                return;
            }
        }
        if (playerOnly && !ctx.isPlayer()) {
            ctx.reply("general.player-only");
            return;
        }
        run(ctx);
    }

    /**
     * Executes the command. For groups this is called when no (known) sub command was given.
     */
    protected void run(CommandContext ctx) {
        sendHelp(ctx, ctx.label());
    }

    public void sendHelp(CommandContext ctx, String label) {
        ctx.reply("help.header", "command", label);
        for (UCCommand child : children.values()) {
            if (ctx.has(child.permission())) {
                if (ctx.messages().exists(child.descriptionKey())) {
                    ctx.reply("help.entry", "command", label + " " + child.usage(),
                            "description", ctx.messages().text(ctx.player(), child.descriptionKey()));
                } else {
                    ctx.reply("help.entry-no-description", "command", label + " " + child.usage());
                }
            }
        }
    }

    protected void usageError(CommandContext ctx) {
        ctx.reply("general.usage", "usage", "/" + ctx.label() + " " + usageArgs());
    }

    /**
     * Argument part of the usage, for example {@code <player> <seconds>}.
     */
    protected String usageArgs() {
        String u = usage();
        int idx = u.indexOf(' ');
        return idx < 0 ? "" : u.substring(idx + 1);
    }

    public final List<String> complete(CommandContext ctx) {
        if (!ctx.has(permission)) {
            return Collections.emptyList();
        }
        if (hasChildren()) {
            if (ctx.size() <= 1) {
                String prefix = ctx.argOr(0, "").toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>();
                for (UCCommand child : children.values()) {
                    if (ctx.has(child.permission()) && child.name().startsWith(prefix)) {
                        out.add(child.name());
                    }
                }
                List<String> own = tab(ctx);
                for (String s : own) {
                    if (s.toLowerCase(Locale.ROOT).startsWith(prefix) && !out.contains(s)) {
                        out.add(s);
                    }
                }
                return out;
            }
            UCCommand sub = lookup.get(ctx.arg(0).toLowerCase(Locale.ROOT));
            if (sub != null) {
                return sub.complete(ctx.shift(sub.name()));
            }
        }
        return filter(tab(ctx), ctx.argOr(ctx.size() - 1, ""));
    }

    /**
     * Tab suggestions for the last argument (unfiltered).
     */
    protected List<String> tab(CommandContext ctx) {
        return Collections.emptyList();
    }

    public static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    public static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }
}
