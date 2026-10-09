package dev.ultimatecombat.integrations;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.SoundFilterHook;
import org.bukkit.Keyed;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * ProtocolLib: hides selected sounds (old-pvp.yml "disable modern sounds") for players with
 * old PvP. If the sound packet cannot be read on this server version, filtering switches itself
 * off and logs one warning.
 */
final class ProtocolLibHook implements Integration, SoundFilterHook {

    private UltimateCombatPlugin plugin;
    private PacketType[] types;
    private PacketAdapter adapter;
    private volatile Set<String> sounds = Set.of();
    private volatile Predicate<UUID> filter = u -> false;
    private volatile boolean broken;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
        Class<?> server = Reflect.type("com.comphenix.protocol.PacketType$Play$Server");
        List<PacketType> found = new ArrayList<>();
        for (String name : List.of("NAMED_SOUND_EFFECT", "ENTITY_SOUND")) {
            try {
                found.add((PacketType) server.getField(name).get(null));
            } catch (ReflectiveOperationException ignored) {
                // not present in this ProtocolLib version
            }
        }
        if (found.isEmpty()) {
            return false;
        }
        types = found.toArray(new PacketType[0]);
        return true;
    }

    @Override
    public String features() {
        return "sound filter";
    }

    @Override
    public synchronized void setFilter(Set<String> sounds, Predicate<UUID> appliesToPlayer) {
        Set<String> normalized = new java.util.HashSet<>();
        for (String s : sounds) {
            String key = s.toLowerCase(Locale.ROOT);
            normalized.add(key.contains(":") ? key : "minecraft:" + key);
        }
        this.sounds = Set.copyOf(normalized);
        this.filter = appliesToPlayer;
        if (adapter == null && !broken) {
            adapter = new PacketAdapter(plugin, ListenerPriority.NORMAL, types) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    handle(event);
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(adapter);
        }
    }

    private void handle(PacketEvent event) {
        if (broken || sounds.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !filter.test(player.getUniqueId())) {
            return;
        }
        try {
            Sound sound = event.getPacket().getSoundEffects().read(0);
            if (sound instanceof Keyed keyed && sounds.contains(keyed.getKey().toString())) {
                event.setCancelled(true);
            }
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            plugin.getLogger().warning("[ProtocolLib] Cannot read sound packets on this server version - the old-pvp sound filter "
                    + "is disabled (" + e + ")");
        }
    }

    @Override
    public synchronized void clearFilter() {
        sounds = Set.of();
        if (adapter != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
            adapter = null;
        }
    }

    @Override
    public void disable() {
        clearFilter();
    }
}
