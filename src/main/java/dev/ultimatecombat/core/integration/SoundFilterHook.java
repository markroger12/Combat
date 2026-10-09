package dev.ultimatecombat.core.integration;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Packet-level sound filter (ProtocolLib). Lets old-pvp hide modern combat sounds such as the
 * sweep, strong/weak attack and critical hit sounds.
 */
public interface SoundFilterHook {

    /**
     * @param sounds          namespaced sound keys to hide
     * @param appliesToPlayer whether the filter is active for a given receiving player
     */
    void setFilter(Set<String> sounds, Predicate<UUID> appliesToPlayer);

    void clearFilter();
}
