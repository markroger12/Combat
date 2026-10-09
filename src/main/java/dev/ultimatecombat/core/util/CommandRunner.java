package dev.ultimatecombat.core.util;

import dev.ultimatecombat.UltimateCombatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Executes administrator-configured commands with placeholders on the correct thread.
 * Console commands run on the global/main thread; player commands run as the player (their own
 * permissions apply) on the player's thread.
 */
public final class CommandRunner {

    private CommandRunner() {
    }

    public static void console(UltimateCombatPlugin plugin, Player context, List<String> commands, Object... placeholders) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (String raw : commands) {
            String cmd = plugin.messages().placeholders(context, raw, placeholders).trim();
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.isEmpty()) {
                continue;
            }
            String finalCmd = cmd;
            plugin.scheduler().executeGlobal(() -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Configured command failed: /" + finalCmd + " (" + e.getMessage() + ")");
                }
            });
        }
    }

    public static void player(UltimateCombatPlugin plugin, Player player, List<String> commands, Object... placeholders) {
        if (commands == null || commands.isEmpty() || player == null) {
            return;
        }
        for (String raw : commands) {
            String cmd = plugin.messages().placeholders(player, raw, placeholders).trim();
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.isEmpty()) {
                continue;
            }
            String finalCmd = cmd;
            plugin.scheduler().executeEntity(player, () -> {
                if (player.isOnline()) {
                    player.performCommand(finalCmd);
                }
            });
        }
    }
}
