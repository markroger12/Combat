package dev.ultimatecombat.core.gui;

import dev.ultimatecombat.UltimateCombatPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Collects one line of chat input (numbers, text, list entries) for GUI editors. The typed line
 * is hidden from chat. Typing the cancel word aborts. Prompts time out automatically.
 */
@SuppressWarnings("deprecation")
public final class ChatPrompt implements Listener {

    private final UltimateCombatPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(Consumer<String> onInput, Runnable onCancel, long expiresAt) {
    }

    public ChatPrompt(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String promptKey, Consumer<String> onInput, Runnable onCancel, Object... placeholders) {
        pending.put(player.getUniqueId(), new Pending(onInput, onCancel, System.currentTimeMillis() + 60_000));
        plugin.scheduler().runEntity(player, player::closeInventory);
        plugin.messages().send(player, promptKey, placeholders);
        plugin.messages().send(player, "gui.prompt-cancel-hint", "word", cancelWord());
    }

    private String cancelWord() {
        return plugin.messages().text("gui.prompt-cancel-word");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        if (System.currentTimeMillis() > p.expiresAt()) {
            plugin.messages().send(player, "gui.prompt-expired");
            return;
        }
        if (message.equalsIgnoreCase(cancelWord()) || message.equalsIgnoreCase("cancel")) {
            plugin.scheduler().runEntity(player, () -> {
                plugin.messages().send(player, "gui.prompt-cancelled");
                if (p.onCancel() != null) {
                    p.onCancel().run();
                }
            });
            return;
        }
        plugin.scheduler().runEntity(player, () -> p.onInput().accept(message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
