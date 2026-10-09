package dev.ultimatecombat.core.module;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import org.bukkit.World;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;

/**
 * Base class of every feature module.
 *
 * <p>Each module owns one YAML file and parses it into an immutable settings snapshot
 * {@code S}. Reloading parses a new snapshot and swaps it atomically, so runtime state (tags,
 * bounties, timers) is never lost. Per-world overrides from {@code worlds.yml} produce additional
 * snapshots returned by {@link #settings(World)}.</p>
 *
 * <p>The module's master on/off switch lives in {@code features.yml}.</p>
 *
 * @param <S> immutable settings type
 */
public abstract class Module<S> implements Listener {

    protected final UltimateCombatPlugin plugin;
    private final String id;
    private final String configName;
    private volatile S settings;
    private volatile Map<String, S> worldSettings = Map.of();
    private volatile boolean enabled;

    protected Module(UltimateCombatPlugin plugin, String id, String configName) {
        this.plugin = plugin;
        this.id = id;
        this.configName = configName;
    }

    /**
     * @return module identifier used in features.yml (for example {@code combat-tag})
     */
    public final String id() {
        return id;
    }

    /**
     * @return YAML file owned by the module (for example {@code combat.yml}), or {@code null}
     */
    public final String configName() {
        return configName;
    }

    /**
     * @return name of the file without extension, used as key in worlds.yml overrides
     */
    public final String configKey() {
        if (configName == null) {
            return id;
        }
        return configName.endsWith(".yml") ? configName.substring(0, configName.length() - 4) : configName;
    }

    /**
     * Parses settings. Must not have side effects besides reporting validation issues.
     */
    protected abstract S parse(ConfigReader reader);

    /**
     * Called after the module was enabled and its listener registered.
     */
    protected void onEnable() {
    }

    /**
     * Called before the listener is unregistered. Must cancel tasks and clean visuals.
     */
    protected void onDisable() {
    }

    /**
     * Called after a reload swapped the settings of an enabled module.
     */
    protected void onReload(S previous) {
    }

    public final S settings() {
        return settings;
    }

    /**
     * @return world-specific settings when an override exists, otherwise the global settings
     */
    public final S settings(World world) {
        if (world == null) {
            return settings;
        }
        Map<String, S> map = worldSettings;
        if (map.isEmpty()) {
            return settings;
        }
        S s = map.get(world.getName().toLowerCase(Locale.ROOT));
        return s == null ? settings : s;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    final void applySettings(S global, Map<String, S> perWorld) {
        this.settings = global;
        this.worldSettings = perWorld;
    }

    final void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    protected final UltimateCombatPlugin plugin() {
        return plugin;
    }
}
