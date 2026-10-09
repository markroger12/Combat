package com.comphenix.protocol;

import com.comphenix.protocol.events.PacketListener;

/** Compile-time stub of ProtocolLib's protocol manager (subset). */
public interface ProtocolManager {

    void addPacketListener(PacketListener listener);

    void removePacketListener(PacketListener listener);
}
