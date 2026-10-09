package dev.ultimatecombat.core.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * One YAML file in the plugin folder together with its bundled, documented defaults.
 *
 * <p>The administrator's file is never rewritten implicitly. Values that are missing fall back
 * to the bundled defaults; invalid values are reported as {@link ValidationIssue}s and replaced
 * by a safe fallback in memory only.</p>
 */
public final class ConfigFile {

    private final String name;
    private final File file;
    private final String resourcePath;
    private final ConfigService service;
    private final Logger logger;

    private volatile YamlConfiguration user = new YamlConfiguration();
    private volatile YamlConfiguration defaults = new YamlConfiguration();
    private volatile boolean parseFailed;
    private volatile YamlConfiguration lastValid;
    private final Map<String, ValidationIssue> issues = Collections.synchronizedMap(new LinkedHashMap<>());

    ConfigFile(ConfigService service, String name, File file, String resourcePath, Logger logger) {
        this.service = service;
        this.name = name;
        this.file = file;
        this.resourcePath = resourcePath;
        this.logger = logger;
    }

    public String name() {
        return name;
    }

    public File file() {
        return file;
    }

    /**
     * (Re)loads the file from disk. Creates it from the bundled default when it does not exist.
     */
    public synchronized void load() {
        issues.clear();
        YamlConfiguration def = new YamlConfiguration();
        try (InputStream in = service.resource(resourcePath)) {
            if (in != null) {
                def.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            logger.severe("Bundled default " + resourcePath + " is invalid: " + e.getMessage());
        }
        if (!file.exists()) {
            service.copyDefault(resourcePath, file);
        }
        YamlConfiguration loaded = new YamlConfiguration();
        parseFailed = false;
        if (file.exists()) {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                loaded.loadFromString(content);
            } catch (IOException e) {
                if (lastValid != null) {
                    loaded = lastValid;
                    report(new ValidationIssue(name, "", null, "File could not be read: " + e.getMessage(),
                            null, "the previously loaded version of this file", ValidationIssue.Severity.ERROR));
                } else {
                    parseFailed = true;
                    report(new ValidationIssue(name, "", null, "File could not be read: " + e.getMessage(),
                            null, "built-in defaults for the whole file", ValidationIssue.Severity.ERROR));
                }
            } catch (InvalidConfigurationException e) {
                String msg = e.getMessage() == null ? "unknown YAML syntax error" : e.getMessage().replace('\n', ' ');
                if (lastValid != null) {
                    // Reload: keep the last successfully loaded version so a typo never resets live settings.
                    report(new ValidationIssue(name, "", null, "YAML syntax error: " + msg,
                            "valid YAML (check indentation and quotes)", "the previously loaded version of this file (your file was NOT modified)",
                            ValidationIssue.Severity.ERROR));
                    loaded = lastValid;
                } else {
                    parseFailed = true;
                    report(new ValidationIssue(name, "", null, "YAML syntax error: " + msg,
                            "valid YAML (check indentation and quotes)", "built-in defaults for the whole file; your file was NOT modified",
                            ValidationIssue.Severity.ERROR));
                    loaded = new YamlConfiguration();
                }
            }
        }
        if (!parseFailed && loaded != lastValid) {
            lastValid = loaded;
        }
        this.defaults = def;
        this.user = loaded;
    }

    /**
     * Returns a reader rooted at the top of this file.
     */
    public ConfigReader reader() {
        return new ConfigReader(this, "", null);
    }

    /**
     * Returns a reader rooted at the top of this file that consults the given overlay first
     * (used for per-world overrides).
     */
    public ConfigReader reader(ConfigurationSection overlay) {
        return new ConfigReader(this, "", overlay);
    }

    YamlConfiguration user() {
        return user;
    }

    YamlConfiguration defaults() {
        return defaults;
    }

    public boolean parseFailed() {
        return parseFailed;
    }

    void report(ValidationIssue issue) {
        String key = issue.path() + "|" + issue.problem();
        if (issues.putIfAbsent(key, issue) == null) {
            service.onIssue(issue);
        }
    }

    public List<ValidationIssue> issues() {
        synchronized (issues) {
            return new ArrayList<>(issues.values());
        }
    }

    /**
     * Raw value lookup without validation: user value, then bundled default.
     */
    public Object rawValue(String path) {
        YamlConfiguration u = user;
        if (u.isSet(path)) {
            return u.get(path);
        }
        return defaults.get(path);
    }

    /**
     * @return whether the path is a section in the administrator's file or the defaults
     */
    public boolean isSection(String path) {
        if (path.isEmpty()) {
            return true;
        }
        YamlConfiguration u = user;
        return u.isSet(path) ? u.isConfigurationSection(path) : defaults.isConfigurationSection(path);
    }

    /**
     * Direct child keys of a section: the administrator's keys plus default keys, except for
     * user-replaced map sections (marked {@code @user-map}) where only the administrator's keys count.
     */
    public Set<String> childKeys(String path) {
        Set<String> out = new java.util.LinkedHashSet<>();
        YamlConfiguration u = user;
        ConfigurationSection userSection = path.isEmpty() ? u : u.getConfigurationSection(path);
        ConfigurationSection defSection = path.isEmpty() ? defaults : defaults.getConfigurationSection(path);
        boolean userMap = !path.isEmpty() && comments(path).stream().anyMatch(c -> c.contains("@user-map"));
        if (defSection != null && !(userMap && userSection != null)) {
            out.addAll(defSection.getKeys(false));
        }
        if (userSection != null) {
            out.addAll(userSection.getKeys(false));
        }
        return out;
    }

    public boolean isSetByUser(String path) {
        return user.isSet(path);
    }

    public boolean existsInDefaults(String path) {
        return defaults.isSet(path);
    }

    /**
     * @return leaf paths that exist in the bundled defaults but not in the administrator's file.
     */
    public Set<String> missingKeys() {
        Set<String> missing = new TreeSet<>();
        if (parseFailed) {
            return missing;
        }
        YamlConfiguration u = user;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (u.isSet(key)) {
                continue;
            }
            // A user-defined map section replaces the default map entirely (for example custom reward rules).
            if (isInsideUserReplacedMap(u, key)) {
                continue;
            }
            missing.add(key);
        }
        return missing;
    }

    private boolean isInsideUserReplacedMap(YamlConfiguration u, String key) {
        String parent = key;
        int idx;
        while ((idx = parent.lastIndexOf('.')) > 0) {
            parent = parent.substring(0, idx);
            List<String> comments = defaults.getComments(parent);
            boolean userMap = comments.stream().filter(Objects::nonNull).anyMatch(c -> c.contains("@user-map"));
            if (userMap && u.isConfigurationSection(parent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the documentation comment written above a key (user file first, then defaults).
     */
    public List<String> comments(String path) {
        List<String> c = user.getComments(path);
        if (c == null || c.isEmpty()) {
            c = defaults.getComments(path);
        }
        List<String> out = new ArrayList<>();
        if (c != null) {
            for (String line : c) {
                out.add(line == null ? "" : line);
            }
        }
        return out;
    }

    /**
     * Writes a value into the administrator's file, keeping comments, after creating a backup.
     *
     * @return {@code true} when written successfully
     */
    public synchronized boolean write(String path, Object value) {
        if (parseFailed) {
            return false;
        }
        try {
            service.backup(this);
            YamlConfiguration current = new YamlConfiguration();
            if (file.exists()) {
                current.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            }
            if (!current.isSet(path) && defaults.isSet(path)) {
                List<String> c = defaults.getComments(path);
                current.set(path, value);
                current.setComments(path, c);
            } else {
                current.set(path, value);
            }
            current.save(file);
            load();
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning("Could not write " + name + " (" + path + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Adds every missing key (with its documentation) from the bundled defaults.
     *
     * @return number of keys added, or -1 on failure
     */
    public synchronized int addMissingKeys() {
        Set<String> missing = missingKeys();
        if (missing.isEmpty()) {
            return 0;
        }
        try {
            service.backup(this);
            YamlConfiguration current = new YamlConfiguration();
            if (file.exists()) {
                current.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            }
            for (String key : missing) {
                ensureParentComments(current, key);
                current.set(key, defaults.get(key));
                current.setComments(key, defaults.getComments(key));
            }
            current.save(file);
            load();
            return missing.size();
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning("Could not add missing keys to " + name + ": " + e.getMessage());
            return -1;
        }
    }

    private void ensureParentComments(YamlConfiguration current, String key) {
        String parent = key;
        int idx;
        List<String> parents = new ArrayList<>();
        while ((idx = parent.lastIndexOf('.')) > 0) {
            parent = parent.substring(0, idx);
            parents.add(0, parent);
        }
        for (String p : parents) {
            if (!current.isSet(p)) {
                current.createSection(p);
                current.setComments(p, defaults.getComments(p));
            }
        }
    }

    /**
     * Applies a structural migration to the administrator's file (after a backup).
     */
    public synchronized boolean migrate(java.util.function.Consumer<YamlConfiguration> migration) {
        if (parseFailed) {
            return false;
        }
        try {
            service.backup(this);
            YamlConfiguration current = new YamlConfiguration();
            current.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            migration.accept(current);
            current.save(file);
            load();
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning("Migration of " + name + " failed: " + e.getMessage());
            return false;
        }
    }
}
