package dev.ultimatecombat.core.command;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.message.MessageService;
import dev.ultimatecombat.core.util.Numbers;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

/**
 * Arguments and helpers passed to every command handler.
 */
public final class CommandContext {

    private final UltimateCombatPlugin plugin;
    private final CommandSender sender;
    private final String label;
    private final String[] args;

    public CommandContext(UltimateCombatPlugin plugin, CommandSender sender, String label, String[] args) {
        this.plugin = plugin;
        this.sender = sender;
        this.label = label;
        this.args = args;
    }

    public UltimateCombatPlugin plugin() {
        return plugin;
    }

    public CommandSender sender() {
        return sender;
    }

    public Player player() {
        return sender instanceof Player p ? p : null;
    }

    public boolean isPlayer() {
        return sender instanceof Player;
    }

    public String label() {
        return label;
    }

    public String[] args() {
        return args;
    }

    public int size() {
        return args.length;
    }

    public String arg(int index) {
        return index < args.length ? args[index] : null;
    }

    public String argOr(int index, String def) {
        return index < args.length ? args[index] : def;
    }

    public String joinFrom(int index) {
        if (index >= args.length) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(args, index, args.length));
    }

    public CommandContext shift(String newLabel) {
        return new CommandContext(plugin, sender, newLabel, args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length));
    }

    public MessageService messages() {
        return plugin.messages();
    }

    public void reply(String key, Object... placeholders) {
        plugin.messages().send(sender, key, placeholders);
    }

    public boolean has(String permission) {
        return permission == null || permission.isEmpty() || sender.hasPermission(permission);
    }

    /**
     * Resolves an online player argument and reports when not found.
     */
    public Player onlinePlayer(int index) {
        String name = arg(index);
        if (name == null) {
            return null;
        }
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            p = Bukkit.getPlayer(name);
        }
        if (p == null) {
            reply("general.player-not-found", "player", name);
        }
        return p;
    }

    public Integer intArg(int index, int min, int max) {
        Integer v = Numbers.parseInt(arg(index));
        if (v == null || v < min || v > max) {
            reply("general.invalid-number", "value", String.valueOf(arg(index)), "min", min, "max", max);
            return null;
        }
        return v;
    }

    public Double doubleArg(int index, double min, double max) {
        Double v = Numbers.parseDouble(arg(index));
        if (v == null || v < min || v > max) {
            reply("general.invalid-number", "value", String.valueOf(arg(index)), "min", Numbers.money(min), "max", Numbers.money(max));
            return null;
        }
        return v;
    }
}
