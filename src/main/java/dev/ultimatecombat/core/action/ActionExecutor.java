package dev.ultimatecombat.core.action;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.util.CommandRunner;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.Registries;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Executes reward actions written as text lines. Shared by rewards.yml, killstreaks.yml and
 * bounties.yml so every feature supports the same actions:
 *
 * <pre>
 * [console] &lt;command&gt;          run a console command
 * [player] &lt;command&gt;           the player runs the command (their own permissions apply)
 * [message] &lt;text&gt;             chat message to the player
 * [broadcast] &lt;text&gt;           chat message to everyone
 * [actionbar] &lt;text&gt;           action bar text
 * [title] &lt;title&gt;;&lt;subtitle&gt;;&lt;in&gt;;&lt;stay&gt;;&lt;out&gt;   title (subtitle and times optional)
 * [subtitle] &lt;text&gt;            only a subtitle
 * [sound] &lt;preset or sound&gt;    sound for the player
 * [particle] &lt;preset&gt;          particle preset at the player
 * [money] &lt;amount&gt;             give money (Vault)
 * [points] &lt;amount&gt;            give (or take, negative) PvP points
 * [item] &lt;MATERIAL&gt; [amount] [name:&lt;text&gt;]   give an item (dropped if the inventory is full)
 * [effect] &lt;EFFECT&gt; &lt;seconds&gt; [amplifier]   potion effect
 * </pre>
 * Every action supports {placeholders} and PlaceholderAPI placeholders.
 */
public final class ActionExecutor {

    private final UltimateCombatPlugin plugin;

    public ActionExecutor(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Validates action lines and reports unknown action types.
     *
     * @return the valid lines
     */
    public static List<String> validate(dev.ultimatecombat.core.config.ConfigReader reader, String path, List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String type = type(line);
            if (type == null) {
                reader.warn(path, line, "Unknown action (line ignored)",
                        "[console], [player], [message], [broadcast], [actionbar], [title], [subtitle], [sound], [particle], "
                                + "[money], [points], [item], [effect]", "line skipped");
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static String type(String line) {
        String t = line.trim();
        if (!t.startsWith("[")) {
            return null;
        }
        int end = t.indexOf(']');
        if (end < 0) {
            return null;
        }
        String type = t.substring(1, end).toLowerCase(Locale.ROOT);
        return switch (type) {
            case "console", "player", "message", "broadcast", "actionbar", "title", "subtitle", "sound", "particle", "money", "points",
                 "item", "effect" -> type;
            default -> null;
        };
    }

    /**
     * Runs actions for a player (who may be {@code null} when offline; player-bound actions are then skipped).
     */
    public void run(Player player, List<String> actions, Map<String, String> placeholders) {
        Object[] ph = new Object[placeholders.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            ph[i++] = e.getKey();
            ph[i++] = e.getValue();
        }
        for (String line : actions) {
            try {
                runLine(player, line, ph);
            } catch (RuntimeException e) {
                Logger log = plugin.getLogger();
                log.warning("Reward action failed: '" + line + "': " + e.getMessage());
            }
        }
    }

    private void runLine(Player player, String line, Object[] ph) {
        String type = type(line);
        if (type == null) {
            return;
        }
        String arg = line.trim().substring(line.trim().indexOf(']') + 1).trim();
        switch (type) {
            case "console" -> CommandRunner.console(plugin, player, List.of(arg), ph);
            case "player" -> CommandRunner.player(plugin, player, List.of(arg), ph);
            case "broadcast" -> {
                String text = plugin.messages().apply(player, arg, ph);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(text);
                }
                Bukkit.getConsoleSender().sendMessage(text);
            }
            default -> {
                if (player == null || !player.isOnline()) {
                    return; // player-bound actions need an online player
                }
                runPlayerAction(player, type, arg, ph);
            }
        }
    }

    private void runPlayerAction(Player player, String type, String arg, Object[] ph) {
        switch (type) {
            case "message" -> player.sendMessage(plugin.messages().apply(player, arg, ph));
            case "actionbar" -> plugin.messages().actionBar(player, plugin.messages().apply(player, arg, ph));
            case "title" -> {
                String[] parts = arg.split(";");
                String title = plugin.messages().apply(player, parts[0], ph);
                String sub = parts.length > 1 ? plugin.messages().apply(player, parts[1], ph) : "";
                int in = parts.length > 2 ? parseInt(parts[2], 10) : 10;
                int stay = parts.length > 3 ? parseInt(parts[3], 40) : 40;
                int out = parts.length > 4 ? parseInt(parts[4], 10) : 10;
                plugin.scheduler().executeEntity(player, () -> plugin.messages().title(player, title, sub, in, stay, out));
            }
            case "subtitle" -> {
                String sub = plugin.messages().apply(player, arg, ph);
                plugin.scheduler().executeEntity(player, () -> plugin.messages().title(player, "", sub, 10, 40, 10));
            }
            case "sound" -> plugin.effects().playSound(player, arg);
            case "particle" -> plugin.effects().spawnParticle(player.getLocation(), arg);
            case "money" -> {
                Double amount = Numbers.parseDouble(plugin.messages().placeholders(player, arg, ph));
                if (amount != null && amount > 0) {
                    plugin.economy().deposit(player, amount, "reward");
                }
            }
            case "points" -> {
                Double amount = Numbers.parseDouble(plugin.messages().placeholders(player, arg, ph));
                if (amount != null) {
                    plugin.players().profile(player).stats().addPointsClamped(Math.round(amount), Long.MIN_VALUE / 4);
                }
            }
            case "item" -> giveItem(player, plugin.messages().placeholders(player, arg, ph));
            case "effect" -> {
                String[] parts = arg.split("\\s+");
                PotionEffectType effect = parts.length > 0 ? Registries.potionEffect(parts[0]) : null;
                if (effect == null) {
                    return;
                }
                int seconds = parts.length > 1 ? parseInt(parts[1], 10) : 10;
                int amp = parts.length > 2 ? Math.max(0, parseInt(parts[2], 1) - 1) : 0;
                plugin.scheduler().executeEntity(player, () -> player.addPotionEffect(new PotionEffect(effect, seconds * 20, amp)));
            }
            default -> {
                // validated before
            }
        }
    }

    private void giveItem(Player player, String spec) {
        String name = null;
        int idx = spec.indexOf("name:");
        String main = spec;
        if (idx >= 0) {
            name = spec.substring(idx + 5).trim();
            main = spec.substring(0, idx).trim();
        }
        String[] parts = main.split("\\s+");
        Material material = Registries.material(parts[0]);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("Reward item action: unknown material " + parts[0]);
            return;
        }
        int amount = parts.length > 1 ? Math.max(1, parseInt(parts[1], 1)) : 1;
        ItemBuilder builder = new ItemBuilder(material);
        if (name != null) {
            builder.name(Text.color(name));
        }
        ItemStack base = builder.build();
        plugin.scheduler().executeEntity(player, () -> {
            int left = amount;
            while (left > 0) {
                ItemStack stack = base.clone();
                stack.setAmount(Math.min(left, material.getMaxStackSize()));
                left -= stack.getAmount();
                for (ItemStack rest : player.getInventory().addItem(stack).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
        });
    }

    private static int parseInt(String s, int def) {
        Integer v = Numbers.parseInt(s.trim());
        return v == null ? def : v;
    }
}
