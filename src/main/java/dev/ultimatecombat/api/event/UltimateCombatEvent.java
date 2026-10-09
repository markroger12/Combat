package dev.ultimatecombat.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/**
 * Base class of UltimateCombat events. Events are fired on the thread that owns the involved
 * player (main thread on Paper/Spigot, the owning region thread on Folia). Events fired from
 * other threads are marked asynchronous automatically.
 */
public abstract class UltimateCombatEvent extends Event {

    protected UltimateCombatEvent() {
        // On Folia isPrimaryThread() is true for every region tick thread.
        super(!Bukkit.isPrimaryThread());
    }
}
