package dev.ultimatecombat.core.module;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Creates, enables, reloads and disables feature modules in registration order (and disables in
 * reverse order). A failure in one module is logged and never prevents other modules from working.
 */
public final class ModuleManager {

    private final UltimateCombatPlugin plugin;
    private final Map<Class<?>, Module<?>> byType = new LinkedHashMap<>();
    private final Map<String, Module<?>> byId = new LinkedHashMap<>();

    public ModuleManager(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public <M extends Module<?>> M register(M module) {
        byType.put(module.getClass(), module);
        byId.put(module.id(), module);
        if (module.configName() != null) {
            plugin.configs().register(module.configName());
        }
        return module;
    }

    @SuppressWarnings("unchecked")
    public <M extends Module<?>> M get(Class<M> type) {
        return (M) byType.get(type);
    }

    public Module<?> get(String id) {
        return byId.get(id);
    }

    public List<Module<?>> all() {
        return new ArrayList<>(byId.values());
    }

    /**
     * @return whether the module is switched on in features.yml
     */
    public boolean switchedOn(Module<?> module) {
        ConfigReader features = plugin.configs().file("features.yml").reader();
        return features.bool("modules." + module.id(), true);
    }

    public void enableAll() {
        for (Module<?> module : byId.values()) {
            parse(module);
            if (switchedOn(module)) {
                enable(module);
            }
        }
    }

    /**
     * Re-parses every module and applies enable/disable changes. Runtime state is preserved.
     */
    public void reloadAll() {
        for (Module<?> module : byId.values()) {
            reload(module);
        }
    }

    public void reload(Module<?> module) {
        boolean shouldRun = switchedOn(module);
        try {
            Object previous = module.settings();
            parse(module);
            if (module.isEnabled() && !shouldRun) {
                disable(module);
            } else if (!module.isEnabled() && shouldRun) {
                enable(module);
            } else if (module.isEnabled()) {
                callReload(module, previous);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Reload of module " + module.id() + " failed; previous settings kept", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <S> void callReload(Module<S> module, Object previous) {
        module.onReload((S) previous);
    }

    private <S> void parse(Module<S> module) {
        if (module.configName() == null) {
            module.applySettings(module.parse(null), Map.of());
            return;
        }
        ConfigFile file = plugin.configs().file(module.configName());
        S global = module.parse(file.reader());
        Map<String, S> perWorld = new HashMap<>();
        ConfigFile worlds = plugin.configs().file("worlds.yml");
        ConfigReader worldsReader = worlds.reader();
        for (String world : worldsReader.keys("overrides")) {
            Object raw = worldsReader.raw("overrides." + world + "." + module.configKey());
            if (raw instanceof ConfigurationSection overlay) {
                S s = module.parse(file.reader(overlay));
                perWorld.put(world.toLowerCase(Locale.ROOT), s);
            }
        }
        module.applySettings(global, perWorld.isEmpty() ? Map.of() : Map.copyOf(perWorld));
    }

    public void enable(Module<?> module) {
        if (module.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getPluginManager().registerEvents(module, plugin);
            module.setEnabled(true);
            module.onEnable();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Module " + module.id() + " could not be enabled", e);
            HandlerList.unregisterAll(module);
            module.setEnabled(false);
        }
    }

    public void disable(Module<?> module) {
        if (!module.isEnabled()) {
            return;
        }
        try {
            module.onDisable();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Module " + module.id() + " failed while disabling", e);
        } finally {
            HandlerList.unregisterAll(module);
            module.setEnabled(false);
        }
    }

    public void disableAll() {
        List<Module<?>> list = new ArrayList<>(byId.values());
        for (int i = list.size() - 1; i >= 0; i--) {
            disable(list.get(i));
        }
    }
}
