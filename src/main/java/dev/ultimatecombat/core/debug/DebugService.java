package dev.ultimatecombat.core.debug;

import dev.ultimatecombat.core.config.ConfigReader;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Optional debug output configured in config.yml. Categories keep the output focused, players can
 * subscribe to live debug messages with /uc debug, and output can be written to logs/.
 */
public final class DebugService {

    private final Logger logger;
    private final File logFolder;
    private volatile boolean enabled;
    private volatile boolean toConsole;
    private volatile boolean toFile;
    private volatile Set<String> categories = Set.of();
    private final Set<UUID> listeners = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private volatile java.util.function.BiConsumer<UUID, String> playerSink;

    public DebugService(Logger logger, File dataFolder) {
        this.logger = logger;
        this.logFolder = new File(dataFolder, "logs");
    }

    public void load(ConfigReader r) {
        enabled = r.bool("enabled", false);
        toConsole = r.bool("console", true);
        toFile = r.bool("file", false);
        categories = r.lowerCaseSet("categories");
    }

    public boolean enabled() {
        return enabled || !listeners.isEmpty();
    }

    public void setPlayerSink(java.util.function.BiConsumer<UUID, String> sink) {
        this.playerSink = sink;
    }

    public boolean toggleListener(UUID player) {
        if (!listeners.remove(player)) {
            listeners.add(player);
            return true;
        }
        return false;
    }

    public void count(String metric) {
        counters.computeIfAbsent(metric, k -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, AtomicLong> counters() {
        return counters;
    }

    public void log(String category, String message) {
        if (!enabled()) {
            return;
        }
        String cat = category.toLowerCase(Locale.ROOT);
        if (!categories.isEmpty() && !categories.contains(cat) && !categories.contains("all")) {
            return;
        }
        String line = "[debug/" + cat + "] " + message;
        if (enabled && toConsole) {
            logger.info(line);
        }
        if (enabled && toFile) {
            writeFile(line);
        }
        java.util.function.BiConsumer<UUID, String> sink = playerSink;
        if (sink != null) {
            for (UUID id : listeners) {
                sink.accept(id, line);
            }
        }
    }

    private synchronized void writeFile(String line) {
        try {
            if (!logFolder.exists() && !logFolder.mkdirs()) {
                return;
            }
            File file = new File(logFolder, "debug-" + LocalDate.now() + ".log");
            try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + " " + line);
                w.newLine();
            }
        } catch (IOException e) {
            toFile = false;
            logger.warning("Debug file logging disabled: " + e.getMessage());
        }
    }
}
