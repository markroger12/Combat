package dev.ultimatecombat;

import dev.ultimatecombat.commands.CoreCommands;
import dev.ultimatecombat.core.action.ActionExecutor;
import dev.ultimatecombat.core.command.CommandManager;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.config.ConfigService;
import dev.ultimatecombat.core.cooldown.CooldownService;
import dev.ultimatecombat.core.debug.DebugService;
import dev.ultimatecombat.core.economy.EconomyService;
import dev.ultimatecombat.core.effect.EffectService;
import dev.ultimatecombat.core.gui.ChatPrompt;
import dev.ultimatecombat.core.gui.MenuListener;
import dev.ultimatecombat.core.gui.MenuService;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.message.MessageService;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.module.ModuleManager;
import dev.ultimatecombat.core.pending.PendingActions;
import dev.ultimatecombat.core.player.PlayerDataService;
import dev.ultimatecombat.core.scheduler.BukkitTaskScheduler;
import dev.ultimatecombat.core.scheduler.FoliaTaskScheduler;
import dev.ultimatecombat.core.scheduler.Platform;
import dev.ultimatecombat.core.scheduler.TaskScheduler;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.storage.Schema;
import dev.ultimatecombat.core.storage.StorageSettings;
import dev.ultimatecombat.core.sync.SyncService;
import dev.ultimatecombat.integrations.Integrations;
import dev.ultimatecombat.modules.ModuleRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Plugin entry point. Wires the core services, feature modules, integrations and commands.
 */
public class UltimateCombatPlugin extends JavaPlugin {

    /**
     * Every YAML file managed by the plugin (module files are registered by their modules).
     */
    public static final List<String> CORE_FILES = List.of(
            "config.yml", "features.yml", "worlds.yml", "messages.yml", "sounds.yml", "particles.yml", "menus.yml",
            "commands.yml", "permissions.yml", "integrations.yml", "storage.yml", "cooldowns.yml");

    private static UltimateCombatPlugin instance;

    private Platform platform;
    private TaskScheduler scheduler;
    private ConfigService configs;
    private DebugService debug;
    private EffectService effects;
    private MessageService messages;
    private Database database;
    private PlayerDataService players;
    private IntegrationManager integrations;
    private MenuService menus;
    private ChatPrompt prompts;
    private ModuleManager modules;
    private CommandManager commands;
    private KillService kills;
    private CooldownService cooldowns;
    private EconomyService economy;
    private PendingActions pending;
    private ActionExecutor actions;
    private SyncService sync;
    private Settings settings;
    private StorageSettings startupStorage;
    private boolean storageReady;

    /**
     * General options from config.yml.
     */
    public record Settings(boolean opsBypass, boolean bypassPermissionsEnabled, Set<String> disabledBypasses,
                           boolean autoMigrate, boolean autoAddMissingKeys, int keepBackups) {
    }

    public static UltimateCombatPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();
        platform = Platform.current();
        scheduler = platform.isFolia() ? new FoliaTaskScheduler(this) : new BukkitTaskScheduler(this, platform);

        configs = new ConfigService(this);
        for (String file : CORE_FILES) {
            configs.register(file);
        }
        debug = new DebugService(getLogger(), getDataFolder());
        effects = new EffectService(configs, scheduler);
        messages = new MessageService(configs, effects);
        integrations = new IntegrationManager(this);
        menus = new MenuService(this);
        prompts = new ChatPrompt(this);
        modules = new ModuleManager(this);
        commands = new CommandManager(this);
        kills = new KillService(this);
        cooldowns = new CooldownService(this);
        economy = new EconomyService(this);
        pending = new PendingActions(this);
        actions = new ActionExecutor(this);

        ModuleRegistry.registerAll(this, modules);
        Integrations.defineAll(this, integrations);
        ConfigMigrations.register(configs);

        configs.loadAll();
        readSettings();
        if (settings.autoMigrate()) {
            for (String applied : configs.runMigrations(true)) {
                getLogger().info("Configuration migrated: " + applied);
            }
        }
        if (settings.autoAddMissingKeys()) {
            for (ConfigFile f : configs.files()) {
                int added = f.addMissingKeys();
                if (added > 0) {
                    getLogger().info("Added " + added + " new documented setting(s) to " + f.name() + " (backup in backups/)");
                }
            }
        }
        reportMissingKeys();
        effects.load();
        messages.load();
        menus.load();
        kills.load();
        cooldowns.load();
        economy.load();
        pending.setExpireMillis(configs.file("storage.yml").reader().durationMillis("pending-actions-expire-days",
                30L * 86_400_000L, ConfigReader.TimeUnitHint.DAYS, 86_400_000L, 3650L * 86_400_000L));

        startDatabase();
        players = new PlayerDataService(database, scheduler, getLogger());
        players.setLoadFailedKickMessage(() -> messages.text("general.data-load-failed"));
        players.configure(startupStorage.loginLoadTimeoutMillis(), startupStorage.denyLoginOnFailure(), startupStorage.autosaveMillis());
        if (storageReady) {
            players.loadOnlinePlayers();
        }
        sync = new SyncService(this);
        sync.start(startupStorage.redis());
        // other servers drop their cached copy of a profile when it changed elsewhere
        players.setSaveListener(id -> sync.publish("profile", id.toString()));
        sync.on("profile", args -> invalidateProfile(args));
        sync.on("pvp-toggle", args -> invalidateProfile(args));

        Bukkit.getPluginManager().registerEvents(players, this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(), this);
        Bukkit.getPluginManager().registerEvents(prompts, this);
        Bukkit.getPluginManager().registerEvents(integrations, this);
        Bukkit.getPluginManager().registerEvents(kills, this);
        Bukkit.getPluginManager().registerEvents(cooldowns, this);
        Bukkit.getPluginManager().registerEvents(pending, this);
        if (storageReady) {
            scheduler.runAsyncTimer(pending::purge, 20L * 60, 20L * 3600);
        }
        debug.setPlayerSink((uuid, line) -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(line);
            }
        });

        integrations.hookAll();
        modules.enableAll();
        CoreCommands.register(this);
        commands.registerAll();

        getLogger().info("UltimateCombat " + getDescription().getVersion() + " enabled on " + platform + " "
                + Platform.minecraftVersion() + " in " + (System.currentTimeMillis() - start) + "ms ("
                + modules.all().stream().filter(Module::isEnabled).count() + "/" + modules.all().size() + " modules active, storage: "
                + (storageReady ? startupStorage.type() : "UNAVAILABLE") + ")");
        int issues = configs.issues().size();
        if (issues > 0) {
            getLogger().warning(issues + " configuration problem(s) found. Safe fallbacks are in use. Run /uc validate for details.");
        }
    }

    private void startDatabase() {
        StorageSettings storage = StorageSettings.read(configs.file("storage.yml").reader());
        // read up front so a mistake is reported at every start, not only when the database fails
        boolean fallback = configs.file("storage.yml").reader().bool("fallback-to-sqlite", true);
        database = new Database(getLogger());
        try {
            database.start(storage, getDataFolder());
            new Schema(database, getLogger()).migrate();
            storageReady = true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not connect to the " + storage.type() + " database: " + Database.rootMessage(e));
            database.shutdown(1000);
            if (storage.type() != Database.Type.SQLITE && fallback) {
                getLogger().severe("Falling back to local SQLite storage (storage.yml: fallback-to-sqlite). "
                        + "Statistics will NOT be shared with other servers until the database is fixed.");
                storage = new StorageSettings(Database.Type.SQLITE, storage.dataGroup(), storage.tablePrefix(), storage.sqliteFile(),
                        storage.host(), storage.port(), storage.database(), storage.username(), storage.password(), storage.useSsl(),
                        storage.properties(), storage.poolSize(), storage.minimumIdle(), storage.connectionTimeoutMillis(),
                        storage.maxLifetimeMillis(), storage.keepaliveMillis(), storage.threads(), storage.autosaveMillis(),
                        storage.shutdownTimeoutMillis(), storage.loginLoadTimeoutMillis(), storage.denyLoginOnFailure(), storage.redis());
                database = new Database(getLogger());
                try {
                    database.start(storage, getDataFolder());
                    new Schema(database, getLogger()).migrate();
                    storageReady = true;
                } catch (Exception ex) {
                    getLogger().log(Level.SEVERE, "SQLite fallback failed too: " + Database.rootMessage(ex)
                            + ". Persistent features (statistics, bounties, pending punishments) will not be saved.");
                }
            } else {
                getLogger().severe("Persistent features (statistics, bounties, pending punishments) will not be saved until storage works.");
            }
        }
        startupStorage = storage;
    }

    private void readSettings() {
        ConfigReader r = configs.file("config.yml").reader();
        debug.load(r.section("debug"));
        configs.setKeepBackups(r.integer("config-management.keep-backups", 20, 1, 1000));
        ConfigReader perms = configs.file("permissions.yml").reader();
        settings = new Settings(
                perms.bool("operators-bypass", false),
                perms.bool("bypass-permissions-enabled", true),
                perms.lowerCaseSet("disabled-bypasses"),
                r.bool("config-management.auto-migrate", true),
                r.bool("config-management.auto-add-missing-keys", false),
                r.integer("config-management.keep-backups", 20, 1, 1000));
    }

    private void reportMissingKeys() {
        List<String> parts = new ArrayList<>();
        for (ConfigFile f : configs.files()) {
            int missing = f.missingKeys().size();
            if (missing > 0) {
                parts.add(f.name() + " (" + missing + ")");
            }
        }
        if (!parts.isEmpty()) {
            getLogger().info("Some files are missing newer settings, defaults are used: " + String.join(", ", parts)
                    + ". Run /uc migrate to add them with documentation (a backup is created first).");
        }
    }

    /**
     * Reloads configuration. {@code target} is {@code all}, a module id or a file name.
     *
     * @return files that were reloaded
     */
    public List<String> reload(String target) {
        List<String> reloaded = new ArrayList<>();
        String t = target == null ? "all" : target.toLowerCase(java.util.Locale.ROOT);
        if (t.equals("all")) {
            configs.loadAll();
            reloaded.add("all");
            readSettings();
            effects.load();
            messages.load();
            menus.load();
            integrations.refreshHooks();
            kills.load();
            cooldowns.load();
            economy.load();
            modules.reloadAll();
            commands.registerStandalone();
            return reloaded;
        }
        if (t.equals("messages") || t.equals("messages.yml") || t.startsWith("languages")) {
            configs.file("messages.yml").load();
            messages.load();
            reloaded.add("messages.yml");
            reloaded.add("languages/");
            return reloaded;
        }
        Module<?> module = modules.get(t);
        ConfigFile file = configs.find(t);
        if (module == null && file != null) {
            for (Module<?> m : modules.all()) {
                if (file.name().equals(m.configName())) {
                    module = m;
                }
            }
        }
        if (module != null) {
            if (module.configName() != null) {
                configs.file(module.configName()).load();
                reloaded.add(module.configName());
            }
            configs.file("features.yml").load();
            configs.file("worlds.yml").load();
            kills.load();
            modules.reload(module);
            return reloaded;
        }
        if (file != null) {
            file.load();
            reloaded.add(file.name());
            switch (file.name()) {
                case "config.yml", "permissions.yml" -> readSettings();
                case "sounds.yml", "particles.yml" -> effects.load();
                case "cooldowns.yml" -> cooldowns.load();
                case "menus.yml" -> menus.load();
                case "commands.yml" -> commands.registerStandalone();
                case "integrations.yml" -> {
                    integrations.refreshHooks();
                    modules.reloadAll();
                }
                case "features.yml", "worlds.yml" -> modules.reloadAll();
                default -> {
                    // storage.yml and others need a restart or are read on demand
                }
            }
        }
        return reloaded;
    }

    /**
     * @return whether storage.yml differs from what the running database uses
     */
    public boolean storageChangedSinceStartup() {
        StorageSettings current = StorageSettings.read(configs.file("storage.yml").reader());
        return startupStorage != null && (current.type() != startupStorage.type()
                || !current.dataGroup().equals(startupStorage.dataGroup())
                || !current.tablePrefix().equals(startupStorage.tablePrefix())
                || !current.host().equals(startupStorage.host())
                || current.port() != startupStorage.port()
                || !current.database().equals(startupStorage.database())
                || current.redis().enabled() != startupStorage.redis().enabled());
    }

    @Override
    public void onDisable() {
        try {
            if (modules != null) {
                modules.disableAll();
            }
            if (commands != null) {
                commands.unregisterAll();
            }
            if (sync != null) {
                sync.stop();
            }
            if (cooldowns != null) {
                cooldowns.shutdown();
            }
            if (players != null) {
                players.shutdown();
                if (storageReady) {
                    players.saveAllBlocking(startupStorage.shutdownTimeoutMillis());
                }
            }
            if (integrations != null) {
                integrations.unhookAll();
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Error during shutdown", e);
        } finally {
            if (database != null) {
                database.shutdown(startupStorage == null ? 10_000 : startupStorage.shutdownTimeoutMillis());
            }
            if (scheduler != null) {
                scheduler.cancelAll();
            }
            HandlerList.unregisterAll(this);
            instance = null;
        }
    }

    // ------------------------------------------------------------------------------ accessors

    public Platform platform() {
        return platform;
    }

    public TaskScheduler scheduler() {
        return scheduler;
    }

    public ConfigService configs() {
        return configs;
    }

    public DebugService debug() {
        return debug;
    }

    public EffectService effects() {
        return effects;
    }

    public MessageService messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public boolean storageReady() {
        return storageReady;
    }

    public PlayerDataService players() {
        return players;
    }

    public IntegrationManager integrations() {
        return integrations;
    }

    public MenuService menus() {
        return menus;
    }

    public ChatPrompt prompts() {
        return prompts;
    }

    public ModuleManager modules() {
        return modules;
    }

    public <M extends Module<?>> M module(Class<M> type) {
        return modules.get(type);
    }

    /**
     * @return the module only when it is enabled, otherwise {@code null}
     */
    public <M extends Module<?>> M active(Class<M> type) {
        M m = modules.get(type);
        return m != null && m.isEnabled() ? m : null;
    }

    public CommandManager commands() {
        return commands;
    }

    public KillService kills() {
        return kills;
    }

    public CooldownService cooldowns() {
        return cooldowns;
    }

    public EconomyService economy() {
        return economy;
    }

    public PendingActions pending() {
        return pending;
    }

    public ActionExecutor actions() {
        return actions;
    }

    public SyncService sync() {
        return sync;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Central bypass check honouring permissions.yml (operators-bypass, disabled-bypasses).
     *
     * @param bypass bypass name, for example {@code combat-tag} for {@code ultimatecombat.bypass.combat-tag}
     */
    public boolean bypasses(Player player, String bypass) {
        Settings s = settings;
        if (!s.bypassPermissionsEnabled() || s.disabledBypasses().contains(bypass)) {
            return false;
        }
        if (s.opsBypass() && player.isOp()) {
            return true;
        }
        // Bypass permissions default to false (even for operators), see plugin.yml.
        return player.hasPermission("ultimatecombat.bypass." + bypass);
    }

    private void invalidateProfile(String[] args) {
        if (args.length == 0) {
            return;
        }
        try {
            players.invalidate(java.util.UUID.fromString(args[0]));
        } catch (IllegalArgumentException ignored) {
            // malformed message from another server
        }
    }

    public File dataFile(String name) {
        return new File(getDataFolder(), name);
    }
}
