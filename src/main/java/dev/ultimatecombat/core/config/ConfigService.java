package dev.ultimatecombat.core.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Central registry of every YAML file. Provides loading, validation reporting, backups,
 * migrations and comment-preserving edits used by the commands and the GUI editor.
 */
public final class ConfigService {

    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Map<String, ConfigFile> files = new LinkedHashMap<>();
    private final Map<String, List<Migration>> migrations = new LinkedHashMap<>();
    private volatile boolean logIssues = true;
    private volatile int keepBackups = 20;
    private volatile String currentBackupStamp;

    /**
     * A structural migration of one file, applied when its {@code config-version} is lower.
     */
    public record Migration(int toVersion, String description, Consumer<YamlConfiguration> action) {
    }

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    /**
     * Registers (but does not load) a file. Name is relative to the plugin folder.
     */
    public synchronized ConfigFile register(String name) {
        return files.computeIfAbsent(name, n -> new ConfigFile(this, n, new File(plugin.getDataFolder(), n), n, logger));
    }

    public synchronized ConfigFile file(String name) {
        ConfigFile f = files.get(name);
        if (f == null) {
            f = register(name);
            f.load();
        }
        return f;
    }

    public synchronized ConfigFile find(String name) {
        ConfigFile f = files.get(name);
        if (f == null && !name.endsWith(".yml")) {
            f = files.get(name + ".yml");
        }
        return f;
    }

    public synchronized List<ConfigFile> files() {
        return new ArrayList<>(files.values());
    }

    public void addMigration(String file, Migration migration) {
        migrations.computeIfAbsent(file, k -> new ArrayList<>()).add(migration);
    }

    public void setLogIssues(boolean logIssues) {
        this.logIssues = logIssues;
    }

    public void setKeepBackups(int keepBackups) {
        this.keepBackups = Math.max(1, keepBackups);
    }

    public synchronized void loadAll() {
        currentBackupStamp = null;
        for (ConfigFile file : files.values()) {
            file.load();
        }
    }

    /**
     * Applies pending versioned migrations to every registered file.
     *
     * @return descriptions of applied migrations
     */
    public synchronized List<String> runMigrations(boolean apply) {
        List<String> applied = new ArrayList<>();
        for (Map.Entry<String, List<Migration>> entry : migrations.entrySet()) {
            ConfigFile file = files.get(entry.getKey());
            if (file == null || file.parseFailed() || !file.file().exists()) {
                continue;
            }
            Object rawVersion = file.user().get("config-version");
            int version = rawVersion instanceof Number n ? n.intValue() : 1;
            List<Migration> pending = entry.getValue().stream()
                    .filter(m -> m.toVersion() > version)
                    .sorted(Comparator.comparingInt(Migration::toVersion))
                    .toList();
            if (pending.isEmpty()) {
                continue;
            }
            if (!apply) {
                for (Migration m : pending) {
                    applied.add(file.name() + " -> v" + m.toVersion() + ": " + m.description() + " (pending)");
                }
                continue;
            }
            boolean ok = file.migrate(cfg -> {
                for (Migration m : pending) {
                    m.action().accept(cfg);
                    cfg.set("config-version", m.toVersion());
                }
            });
            for (Migration m : pending) {
                applied.add(file.name() + " -> v" + m.toVersion() + ": " + m.description() + (ok ? "" : " (FAILED)"));
            }
        }
        return applied;
    }

    public List<ValidationIssue> issues() {
        List<ValidationIssue> all = new ArrayList<>();
        for (ConfigFile file : files()) {
            all.addAll(file.issues());
        }
        return all;
    }

    void onIssue(ValidationIssue issue) {
        if (!logIssues) {
            return;
        }
        switch (issue.severity()) {
            case ERROR -> logger.severe("Config error " + issue.describe());
            case WARNING -> logger.warning("Config warning " + issue.describe());
            case INFO -> logger.info("Config notice " + issue.describe());
        }
    }

    InputStream resource(String path) {
        return plugin.getResource(path.replace('\\', '/'));
    }

    void copyDefault(String resourcePath, File target) {
        try (InputStream in = resource(resourcePath)) {
            if (in == null) {
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Could not create folder " + parent);
                return;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Could not create default " + resourcePath + ": " + e.getMessage());
        }
    }

    /**
     * Copies a bundled resource into the plugin folder only when it does not exist yet.
     */
    public void saveResourceIfMissing(String path) {
        File target = new File(plugin.getDataFolder(), path);
        if (!target.exists()) {
            copyDefault(path, target);
        }
    }

    /**
     * Creates a timestamped backup of the given file inside {@code backups/}. Several files changed
     * by one action share the same backup folder.
     */
    synchronized void backup(ConfigFile file) throws IOException {
        if (!file.file().exists()) {
            return;
        }
        if (currentBackupStamp == null) {
            currentBackupStamp = LocalDateTime.now().format(BACKUP_FORMAT);
        }
        Path dir = plugin.getDataFolder().toPath().resolve("backups").resolve(currentBackupStamp);
        Path target = dir.resolve(file.name());
        Files.createDirectories(target.getParent());
        Files.copy(file.file().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        pruneBackups();
    }

    /**
     * Starts a new backup folder for the next change (called once per admin action).
     */
    public synchronized void newBackupSession() {
        currentBackupStamp = null;
    }

    private void pruneBackups() {
        Path dir = plugin.getDataFolder().toPath().resolve("backups");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> folders = stream.filter(Files::isDirectory).sorted().toList();
            int excess = folders.size() - keepBackups;
            for (int i = 0; i < excess; i++) {
                deleteRecursively(folders.get(i));
            }
        } catch (IOException e) {
            logger.fine("Could not prune backups: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Creates a full backup of every YAML file (used by {@code /uc backup} and before migrations).
     */
    public synchronized int backupAll() {
        newBackupSession();
        int count = 0;
        for (ConfigFile file : files.values()) {
            try {
                backup(file);
                count++;
            } catch (IOException e) {
                logger.warning("Backup of " + file.name() + " failed: " + e.getMessage());
            }
        }
        newBackupSession();
        return count;
    }

    /**
     * Resolves a {@code file:path} or {@code file.yml:path} reference used by commands.
     */
    public Reference resolve(String reference) {
        int idx = reference.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        ConfigFile file = find(reference.substring(0, idx));
        if (file == null) {
            return null;
        }
        return new Reference(file, reference.substring(idx + 1));
    }

    public record Reference(ConfigFile file, String path) {
    }

    /**
     * Converts a text value typed by an administrator into the YAML type of the existing setting.
     *
     * @return converted value, or {@code null} when the text does not fit the setting's type
     */
    public static Object coerce(Object current, String text) {
        String t = text.trim();
        if (current instanceof Boolean) {
            if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(t);
            }
            return null;
        }
        if (current instanceof Integer || current instanceof Long) {
            try {
                return Long.parseLong(t) <= Integer.MAX_VALUE && Long.parseLong(t) >= Integer.MIN_VALUE
                        ? (Object) Integer.parseInt(t) : (Object) Long.parseLong(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (current instanceof Double || current instanceof Float) {
            try {
                return Double.parseDouble(t.replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (current instanceof List<?>) {
            if (t.equals("[]") || t.isEmpty()) {
                return new ArrayList<String>();
            }
            List<String> out = new ArrayList<>();
            for (String part : t.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        if (current instanceof ConfigurationSection) {
            return null;
        }
        return text;
    }
}
