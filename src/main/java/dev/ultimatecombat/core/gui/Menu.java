package dev.ultimatecombat.core.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest menu whose slots carry click handlers. Every click inside a menu is cancelled, so items
 * can never be taken out of or put into a menu.
 */
public class Menu implements InventoryHolder {

    @FunctionalInterface
    public interface ClickHandler {
        void onClick(Player player, ClickType click);
    }

    private final Inventory inventory;
    private final Map<Integer, ClickHandler> handlers = new HashMap<>();
    private Consumer<Player> onClose;
    private Consumer<InventoryClickEvent> bottomClickHandler;

    @SuppressWarnings("deprecation")
    public Menu(int rows, String title) {
        int size = Math.max(1, Math.min(6, rows)) * 9;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public int size() {
        return inventory.getSize();
    }

    public Menu set(int slot, ItemStack item, ClickHandler handler) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return this;
        }
        inventory.setItem(slot, item);
        if (handler != null) {
            handlers.put(slot, handler);
        } else {
            handlers.remove(slot);
        }
        return this;
    }

    public Menu set(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    public void clear() {
        inventory.clear();
        handlers.clear();
    }

    public Menu onClose(Consumer<Player> handler) {
        this.onClose = handler;
        return this;
    }

    /**
     * Allows handling clicks in the player's own inventory while the menu is open (used by editors).
     */
    public Menu onBottomClick(Consumer<InventoryClickEvent> handler) {
        this.bottomClickHandler = handler;
        return this;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null) {
            return;
        }
        if (event.getClickedInventory() != inventory) {
            if (bottomClickHandler != null) {
                bottomClickHandler.accept(event);
            }
            return;
        }
        ClickHandler handler = handlers.get(event.getSlot());
        if (handler != null) {
            handler.onClick(player, event.getClick());
        }
    }

    void handleClose(Player player) {
        if (onClose != null) {
            onClose.accept(player);
        }
    }
}
