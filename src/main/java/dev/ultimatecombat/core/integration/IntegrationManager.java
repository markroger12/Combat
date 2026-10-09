package dev.ultimatecombat.core.integration;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Detects installed plugins and activates the matching hooks. Every integration can be switched
 * off in integrations.yml. A missing or failing integration never affects the rest of the plugin.
 */
public final class IntegrationManager implements Listener {

    /**
     * Static description of a supported integration.
     */
    public record Definition(String id, String pluginName, Supplier<Integration> factory) {
    }

    public enum Status { ACTIVE, DISABLED_IN_CONFIG, NOT_INSTALLED, FAILED }

    public record State(Definition definition, Status status, String detail, Integration instance) {
    }

    private static final ThreadLocal<Boolean> SIMULATING = ThreadLocal.withInitial(() -> false);

    private final UltimateCombatPlugin plugin;
    private final List<Definition> definitions = new ArrayList<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final List<ProtectionHook> protections = new CopyOnWriteArrayList<>();
    private final List<AllyHook> allies = new CopyOnWriteArrayList<>();
    private final List<PlayerStateHook> playerStates = new CopyOnWriteArrayList<>();
    private final List<EntityHook> entities = new CopyOnWriteArrayList<>();
    private final List<ItemHook> items = new CopyOnWriteArrayList<>();
    private volatile EconomyHook economy;
    private volatile PlaceholderHook placeholders;
    private volatile NpcHook npcs;
    private volatile SoundFilterHook soundFilter;
    private volatile boolean genericProtectionCheck;
    private volatile boolean vanishMetadata = true;

    public IntegrationManager(UltimateCombatPlugin plugin) {
        this.plugin = plugin;
    }

    public void define(String id, String pluginName, Supplier<Integration> factory) {
        definitions.add(new Definition(id, pluginName, factory));
    }

    public List<Definition> definitions() {
        return List.copyOf(definitions);
    }

    /**
     * @return whether the current thread is firing a simulated damage event (listeners must ignore it)
     */
    public static boolean isSimulating() {
        return SIMULATING.get();
    }

    public void loadSettings() {
        ConfigReader r = plugin.configs().file("integrations.yml").reader();
        genericProtectionCheck = r.bool("generic-protection-check.enabled", false);
        vanishMetadata = r.bool("vanish-metadata.enabled", true);
    }

    public void hookAll() {
        loadSettings();
        for (Definition def : definitions) {
            hook(def);
        }
    }

    private boolean configEnabled(Definition def) {
        ConfigReader r = plugin.configs().file("integrations.yml").reader();
        return r.bool("hooks." + def.id() + ".enabled", true);
    }

    private synchronized void hook(Definition def) {
        State existing = states.get(def.id());
        if (existing != null && existing.status() == Status.ACTIVE) {
            return;
        }
        if (!configEnabled(def)) {
            states.put(def.id(), new State(def, Status.DISABLED_IN_CONFIG, "", null));
            return;
        }
        Plugin other = Bukkit.getPluginManager().getPlugin(def.pluginName());
        if (other == null || !other.isEnabled()) {
            states.put(def.id(), new State(def, Status.NOT_INSTALLED, "", null));
            return;
        }
        try {
            Integration integration = def.factory().get();
            if (!integration.enable(plugin)) {
                states.put(def.id(), new State(def, Status.FAILED, "hook refused (unsupported version?)", null));
                return;
            }
            register(integration);
            states.put(def.id(), new State(def, Status.ACTIVE, integration.features(), integration));
            plugin.getLogger().info("Hooked into " + def.pluginName() + " " + other.getDescription().getVersion()
                    + " (" + integration.features() + ")");
        } catch (Throwable t) {
            states.put(def.id(), new State(def, Status.FAILED, t.getClass().getSimpleName() + ": " + t.getMessage(), null));
            plugin.getLogger().log(Level.WARNING, "Could not hook into " + def.pluginName() + " (the feature stays disabled): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            if (plugin.debug().enabled()) {
                plugin.getLogger().log(Level.WARNING, "Integration failure details", t);
            }
        }
    }

    private void register(Integration integration) {
        if (integration instanceof ProtectionHook h) protections.add(h);
        if (integration instanceof AllyHook h) allies.add(h);
        if (integration instanceof PlayerStateHook h) playerStates.add(h);
        if (integration instanceof EntityHook h) entities.add(h);
        if (integration instanceof ItemHook h) items.add(h);
        if (integration instanceof EconomyHook h && economy == null) economy = h;
        if (integration instanceof PlaceholderHook h && placeholders == null) {
            placeholders = h;
            plugin.messages().setExternalPlaceholders(h::apply);
        }
        if (integration instanceof NpcHook h && npcs == null) npcs = h;
        if (integration instanceof SoundFilterHook h && soundFilter == null) soundFilter = h;
    }

    private void unregister(Integration integration) {
        protections.remove(integration);
        allies.remove(integration);
        playerStates.remove(integration);
        entities.remove(integration);
        items.remove(integration);
        if (economy == integration) economy = null;
        if (placeholders == integration) {
            placeholders = null;
            plugin.messages().setExternalPlaceholders(null);
        }
        if (npcs == integration) npcs = null;
        if (soundFilter == integration) soundFilter = null;
    }

    public synchronized void unhookAll() {
        for (State state : states.values()) {
            if (state.instance() != null) {
                try {
                    state.instance().disable();
                } catch (Throwable t) {
                    plugin.getLogger().fine("Error while unhooking " + state.definition().pluginName() + ": " + t.getMessage());
                }
                unregister(state.instance());
            }
        }
        states.clear();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        for (Definition def : definitions) {
            if (def.pluginName().equalsIgnoreCase(event.getPlugin().getName())) {
                plugin.scheduler().runGlobalLater(() -> hook(def), 1L);
            }
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        synchronized (this) {
            for (Map.Entry<String, State> e : new HashMap<>(states).entrySet()) {
                State s = e.getValue();
                if (s.instance() != null && s.definition().pluginName().equalsIgnoreCase(event.getPlugin().getName())) {
                    try {
                        s.instance().disable();
                    } catch (Throwable ignored) {
                        // plugin is going away anyway
                    }
                    unregister(s.instance());
                    states.put(e.getKey(), new State(s.definition(), Status.NOT_INSTALLED, "plugin disabled", null));
                }
            }
        }
    }

    public synchronized List<State> states() {
        return new ArrayList<>(states.values());
    }

    public boolean isActive(String id) {
        State s = states.get(id);
        return s != null && s.status() == Status.ACTIVE;
    }

    // --------------------------------------------------------------------------- aggregated queries

    public EconomyHook economy() {
        return economy;
    }

    public PlaceholderHook placeholders() {
        return placeholders;
    }

    public NpcHook npcs() {
        return npcs;
    }

    public SoundFilterHook soundFilter() {
        return soundFilter;
    }

    /**
     * @return {@code false} when any protection plugin denies PvP at the location
     */
    public boolean isPvpAllowedAt(Location location) {
        for (ProtectionHook h : protections) {
            try {
                if (!h.isPvpAllowed(location)) {
                    return false;
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.debug().log("integrations", "Protection hook " + h.getClass().getSimpleName() + " failed: " + e);
            }
        }
        return true;
    }

    public boolean hasProtectionHooks() {
        return !protections.isEmpty() || genericProtectionCheck;
    }

    /**
     * @return {@code false} when any protection plugin denies this attack
     */
    public boolean canAttack(Player attacker, Player victim) {
        for (ProtectionHook h : protections) {
            try {
                if (!h.canAttack(attacker, victim)) {
                    return false;
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.debug().log("integrations", "Protection hook " + h.getClass().getSimpleName() + " failed: " + e);
            }
        }
        if (genericProtectionCheck && !simulateAttack(attacker, victim)) {
            return false;
        }
        return true;
    }

    /**
     * Asks every installed plugin whether the attack would be allowed by firing a harmless
     * simulated damage event. UltimateCombat's own listeners ignore simulated events.
     */
    @SuppressWarnings({"deprecation", "removal"})
    public boolean simulateAttack(Player attacker, Player victim) {
        if (!genericProtectionCheck) {
            return true;
        }
        SIMULATING.set(true);
        try {
            EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, victim,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                    org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                            .withCausingEntity(attacker).withDirectEntity(attacker).build(),
                    0.0001);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } catch (RuntimeException | LinkageError e) {
            plugin.debug().log("integrations", "Simulated attack check failed: " + e);
            return true;
        } finally {
            SIMULATING.set(false);
        }
    }

    public Set<String> regionsAt(Location location) {
        Set<String> out = new TreeSet<>();
        for (ProtectionHook h : protections) {
            try {
                for (String r : h.regionsAt(location)) {
                    out.add(r.toLowerCase(Locale.ROOT));
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.debug().log("integrations", "Region lookup failed: " + e);
            }
        }
        return out;
    }

    public boolean areAllies(Player a, Player b) {
        for (AllyHook h : allies) {
            try {
                if (h.areAllies(a, b)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.debug().log("integrations", "Ally hook " + h.getClass().getSimpleName() + " failed: " + e);
            }
        }
        return false;
    }

    public boolean isVanished(Player player) {
        if (vanishMetadata) {
            for (MetadataValue v : player.getMetadata("vanished")) {
                if (v.asBoolean()) {
                    return true;
                }
            }
        }
        for (PlayerStateHook h : playerStates) {
            try {
                if (h.isVanished(player)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return false;
    }

    public void setVanished(Player player, boolean vanished) {
        for (PlayerStateHook h : playerStates) {
            try {
                h.setVanished(player, vanished);
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
    }

    public boolean isGodMode(Player player) {
        for (PlayerStateHook h : playerStates) {
            try {
                if (h.isGodMode(player)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return false;
    }

    public void disableGodMode(Player player) {
        for (PlayerStateHook h : playerStates) {
            try {
                h.setGodMode(player, false);
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
    }

    public boolean isDisguised(Player player) {
        for (PlayerStateHook h : playerStates) {
            try {
                if (h.isDisguised(player)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return false;
    }

    public void undisguise(Player player) {
        for (PlayerStateHook h : playerStates) {
            try {
                h.undisguise(player);
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
    }

    public String customEntityId(Entity entity) {
        for (EntityHook h : entities) {
            try {
                String id = h.customId(entity);
                if (id != null) {
                    return id;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return null;
    }

    public boolean isCustomBoss(Entity entity) {
        for (EntityHook h : entities) {
            try {
                if (h.isBoss(entity)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return false;
    }

    public String customItemId(ItemStack item) {
        for (ItemHook h : items) {
            try {
                String id = h.customItemId(item);
                if (id != null) {
                    return id;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return null;
    }

    public Map<String, Integer> customEnchants(ItemStack item) {
        Map<String, Integer> out = new HashMap<>();
        for (ItemHook h : items) {
            try {
                out.putAll(h.customEnchants(item));
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return out;
    }

    public ItemStack removeCustomEnchant(ItemStack item, String id) {
        ItemStack current = item;
        for (ItemHook h : items) {
            try {
                current = h.removeCustomEnchant(current, id);
            } catch (RuntimeException | LinkageError ignored) {
                // ignore broken hook
            }
        }
        return current;
    }
}
