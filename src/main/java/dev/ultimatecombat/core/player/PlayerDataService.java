package dev.ultimatecombat.core.player;

import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.scheduler.TaskScheduler;
import dev.ultimatecombat.core.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Owns the in-memory profiles of online players and their persistence.
 *
 * <ul>
 *     <li>Profiles are loaded in {@link AsyncPlayerPreLoginEvent} (already off the main thread).</li>
 *     <li>Saves for the same player are chained so they can never overtake each other.</li>
 *     <li>A player who rejoins while their quit-save is still running reuses the in-memory profile,
 *     so no stale data is read back.</li>
 *     <li>If loading fails the profile is flagged and never overwrites stored state.</li>
 * </ul>
 */
public final class PlayerDataService implements Listener {

    private final Database database;
    private final ProfileRepository repository;
    private final TaskScheduler scheduler;
    private final Logger logger;
    private final Map<UUID, PlayerProfile> online = new ConcurrentHashMap<>();
    private final Map<UUID, PreLoaded> preloaded = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerProfile> saving = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> saveChains = new ConcurrentHashMap<>();
    private volatile Consumer<UUID> saveListener;
    private final Map<UUID, PlayerProfile> offlineCache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, PlayerProfile> eldest) {
            return size() > 256;
        }
    });
    private final List<Consumer<PlayerProfile>> joinHooks = new ArrayList<>();
    private volatile Supplier<String> ipSalt = () -> null;
    private volatile Supplier<String> loadFailedKickMessage = () -> "";
    private volatile long loadTimeoutMillis = 5000;
    private volatile boolean denyOnFailure;
    private ScheduledTask autosave;

    private record PreLoaded(PlayerProfile profile, long at) {
    }

    public PlayerDataService(Database database, TaskScheduler scheduler, Logger logger) {
        this.database = database;
        this.repository = new ProfileRepository(database);
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public ProfileRepository repository() {
        return repository;
    }

    public void configure(long loadTimeoutMillis, boolean denyOnFailure, long autosaveMillis) {
        this.loadTimeoutMillis = loadTimeoutMillis;
        this.denyOnFailure = denyOnFailure;
        if (autosave != null) {
            autosave.cancel();
        }
        long ticks = Math.max(20, autosaveMillis / 50);
        autosave = scheduler.runAsyncTimer(this::saveDirty, ticks, ticks);
    }

    /**
     * Supplies the salt used to hash IP addresses; {@code null} disables IP hashing entirely.
     */
    public void setIpSalt(Supplier<String> salt) {
        this.ipSalt = salt;
    }

    public void setLoadFailedKickMessage(Supplier<String> message) {
        this.loadFailedKickMessage = message;
    }

    /**
     * Registers a callback invoked on the player's thread after a profile became active on join.
     */
    public void onProfileJoin(Consumer<PlayerProfile> hook) {
        joinHooks.add(hook);
    }

    /**
     * Loads profiles of players already online (plugin reload / late enable). Blocks only during
     * start-up.
     */
    public void loadOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile;
            try {
                profile = database.supplySync(c -> repository.load(c, p.getUniqueId(), p.getName()));
            } catch (Exception e) {
                logger.warning("Could not load profile of " + p.getName() + ": " + Database.rootMessage(e));
                profile = new PlayerProfile(p.getUniqueId(), p.getName());
                profile.loadFailed(true);
            }
            online.put(p.getUniqueId(), profile);
        }
    }

    // --------------------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        PlayerProfile reuse = online.get(uuid);
        if (reuse == null) {
            reuse = saving.get(uuid);
        }
        PlayerProfile profile;
        if (reuse != null) {
            profile = reuse;
        } else {
            CompletableFuture<Void> chain = saveChains.get(uuid);
            try {
                if (chain != null) {
                    chain.get(loadTimeoutMillis, TimeUnit.MILLISECONDS);
                }
                profile = database.supply(c -> repository.load(c, uuid, event.getName()))
                        .get(loadTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.warning("Could not load data of " + event.getName() + ": " + Database.rootMessage(e));
                if (denyOnFailure) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, loadFailedKickMessage.get());
                    return;
                }
                profile = new PlayerProfile(uuid, event.getName());
                profile.loadFailed(true);
            }
        }
        profile.name(event.getName());
        String salt = ipSalt.get();
        if (salt != null && event.getAddress() != null) {
            profile.ipHash(hashIp(salt, event.getAddress().getHostAddress()));
        }
        preloaded.put(uuid, new PreLoaded(profile, System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PreLoaded pre = preloaded.remove(uuid);
        PlayerProfile profile = pre != null ? pre.profile() : online.get(uuid);
        if (profile == null) {
            profile = saving.get(uuid);
        }
        if (profile == null) {
            // Pre-login was skipped (for example by another plugin); load asynchronously.
            PlayerProfile placeholder = new PlayerProfile(uuid, player.getName());
            placeholder.loadFailed(true);
            profile = placeholder;
            database.supply(c -> repository.load(c, uuid, player.getName())).thenAccept(loaded ->
                    scheduler.runEntity(player, () -> {
                        if (online.get(uuid) == placeholder) {
                            online.put(uuid, loaded);
                            joinHooks.forEach(h -> h.accept(loaded));
                        }
                    }));
        }
        offlineCache.remove(uuid);
        profile.lastSeen(System.currentTimeMillis());
        online.put(uuid, profile);
        PlayerProfile active = profile;
        joinHooks.forEach(h -> h.accept(active));
        cleanupPreloaded();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Run after every other quit handler of this tick (combat log punishments update stats).
        PlayerProfile profile = online.get(uuid);
        if (profile == null) {
            return;
        }
        profile.lastSeen(System.currentTimeMillis());
        scheduler.runGlobal(() -> {
            if (Bukkit.getPlayer(uuid) != null) {
                return; // rejoined already, keep the profile online
            }
            PlayerProfile removed = online.remove(uuid);
            if (removed == null) {
                return;
            }
            saving.put(uuid, removed);
            save(removed).whenComplete((v, t) -> saving.remove(uuid, removed));
        });
    }

    private void cleanupPreloaded() {
        long cutoff = System.currentTimeMillis() - 120_000;
        preloaded.entrySet().removeIf(e -> e.getValue().at() < cutoff);
    }

    // --------------------------------------------------------------------------- access

    public PlayerProfile profile(Player player) {
        PlayerProfile p = online.get(player.getUniqueId());
        if (p == null) {
            p = new PlayerProfile(player.getUniqueId(), player.getName());
            p.loadFailed(true);
            PlayerProfile existing = online.putIfAbsent(player.getUniqueId(), p);
            if (existing != null) {
                p = existing;
            }
        }
        return p;
    }

    public PlayerProfile online(UUID uuid) {
        return online.get(uuid);
    }

    public Collection<PlayerProfile> onlineProfiles() {
        return online.values();
    }

    /**
     * Looks up a profile (online, cached or from the database).
     */
    public CompletableFuture<Optional<PlayerProfile>> lookup(UUID uuid) {
        PlayerProfile p = online.get(uuid);
        if (p == null) {
            p = saving.get(uuid);
        }
        if (p == null) {
            p = offlineCache.get(uuid);
        }
        if (p != null) {
            return CompletableFuture.completedFuture(Optional.of(p));
        }
        return database.supply(c -> repository.load(c, uuid, null)).thenApply(loaded -> {
            if (!loaded.existsInDatabase() && loaded.stats().kills() == 0 && loaded.stats().deaths() == 0) {
                return Optional.<PlayerProfile>empty();
            }
            offlineCache.put(uuid, loaded);
            return Optional.of(loaded);
        });
    }

    public CompletableFuture<Optional<PlayerProfile>> lookup(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            return CompletableFuture.completedFuture(Optional.of(profile(p)));
        }
        return database.supply(c -> Optional.ofNullable(repository.loadByName(c, name))).thenApply(opt -> {
            opt.ifPresent(pr -> offlineCache.put(pr.uuid(), pr));
            return opt;
        });
    }

    /**
     * Applies a change to a possibly offline player and persists it.
     */
    public CompletableFuture<Boolean> modify(UUID uuid, Consumer<PlayerProfile> change) {
        return lookup(uuid).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            PlayerProfile profile = opt.get();
            change.accept(profile);
            profile.markDirty();
            if (online.containsKey(uuid)) {
                return CompletableFuture.completedFuture(true);
            }
            return save(profile).thenApply(v -> true);
        });
    }

    // --------------------------------------------------------------------------- saving

    public CompletableFuture<Void> save(PlayerProfile profile) {
        UUID uuid = profile.uuid();
        CompletableFuture<Void> result = saveChains.compute(uuid, (k, previous) -> {
            CompletableFuture<Void> base = previous == null ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(t -> null);
            return base.thenCompose(v -> {
                if (!profile.isDirty() && !profile.stats().isDirty() && !profile.statsReset()) {
                    return CompletableFuture.completedFuture(null);
                }
                PlayerStats.Delta delta = profile.stats().drain();
                return database.transaction(c -> {
                    repository.save(c, profile, delta);
                    return null;
                }).handle((r, t) -> {
                    if (t != null) {
                        // keep the changes; they are written by the next save attempt
                        profile.stats().restore(delta);
                        profile.markDirty();
                        logger.warning("Could not save data of " + profile.name() + " (will retry): " + Database.rootMessage(t));
                    } else {
                        profile.markClean();
                        Consumer<UUID> listener = saveListener;
                        if (listener != null) {
                            listener.accept(uuid);
                        }
                    }
                    return null;
                });
            });
        });
        result.whenComplete((v, t) -> saveChains.remove(uuid, result));
        return result;
    }

    /**
     * Called (on the database thread) after a profile was written; used to tell other servers.
     */
    public void setSaveListener(Consumer<UUID> listener) {
        this.saveListener = listener;
    }

    /**
     * Forgets the cached copy of an offline player so the next lookup reads the database again
     * (another server changed the data).
     */
    public void invalidate(UUID uuid) {
        offlineCache.remove(uuid);
    }

    public void saveDirty() {
        for (PlayerProfile profile : online.values()) {
            if (profile.isDirty() || profile.stats().isDirty() || profile.statsReset()) {
                save(profile);
            }
        }
    }

    /**
     * Saves everything and waits (bounded) for completion. Used on plugin disable.
     */
    public void saveAllBlocking(long timeoutMillis) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PlayerProfile profile : online.values()) {
            futures.add(save(profile));
        }
        for (PlayerProfile profile : saving.values()) {
            futures.add(save(profile));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warning("Not every profile could be saved during shutdown: " + Database.rootMessage(e));
        }
    }

    public void shutdown() {
        if (autosave != null) {
            autosave.cancel();
        }
    }

    public static String hashIp(String salt, String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
