package me.clip.placeholderapi.expansion;

import org.bukkit.OfflinePlayer;

/** Compile-time stub of PlaceholderAPI's expansion base class (real class provided by PlaceholderAPI). */
public abstract class PlaceholderExpansion {

    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public boolean persist() {
        return false;
    }

    public boolean canRegister() {
        return true;
    }

    public String onRequest(OfflinePlayer player, String params) {
        return null;
    }

    public boolean register() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean unregister() {
        throw new UnsupportedOperationException("stub");
    }
}
