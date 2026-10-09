package com.comphenix.protocol.events;

import com.comphenix.protocol.PacketType;
import org.bukkit.plugin.Plugin;

/** Compile-time stub of ProtocolLib's packet adapter base class. */
public abstract class PacketAdapter implements PacketListener {

    public PacketAdapter(Plugin plugin, ListenerPriority priority, PacketType... types) {
    }

    public void onPacketSending(PacketEvent event) {
    }

    public void onPacketReceiving(PacketEvent event) {
    }
}
