package dev.ultimatecombat.integrations;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.integration.ExpansionHost;
import dev.ultimatecombat.core.integration.Integration;
import dev.ultimatecombat.core.integration.PlaceholderHook;
import dev.ultimatecombat.modules.placeholders.PlaceholderModule;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI: resolves other plugins' placeholders in UltimateCombat messages and registers
 * the UltimateCombat expansion (placeholders.yml).
 */
final class PlaceholderApiHook implements Integration, PlaceholderHook, ExpansionHost {

    private UltimateCombatPlugin plugin;
    private Expansion expansion;

    @Override
    public boolean enable(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
        registerExpansion();
        return true;
    }

    /**
     * (Re-)registers the expansion, for example after the identifier changed in placeholders.yml.
     */
    @Override
    public synchronized void registerExpansion() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        PlaceholderModule module = plugin.module(PlaceholderModule.class);
        if (module == null || !module.isEnabled()) {
            return;
        }
        Expansion e = new Expansion(plugin, module.settings().identifier());
        if (e.register()) {
            expansion = e;
        } else {
            plugin.getLogger().warning("PlaceholderAPI refused the expansion '" + module.settings().identifier()
                    + "' (identifier already used by another expansion?)");
        }
    }

    @Override
    public synchronized void unregisterExpansion() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
    }

    @Override
    public void disable() {
        unregisterExpansion();
    }

    @Override
    public String features() {
        return expansion == null ? "external placeholders" : "external placeholders, %" + expansion.getIdentifier() + "_...%";
    }

    @Override
    public String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    private static final class Expansion extends PlaceholderExpansion {

        private final UltimateCombatPlugin plugin;
        private final String identifier;

        Expansion(UltimateCombatPlugin plugin, String identifier) {
            this.plugin = plugin;
            this.identifier = identifier;
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public String getAuthor() {
            return "UltimateCombat";
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true; // survive /papi reload; UltimateCombat unregisters it itself
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            PlaceholderModule module = plugin.module(PlaceholderModule.class);
            if (module == null || !module.isEnabled()) {
                return null;
            }
            return module.resolve(player, params);
        }
    }
}
