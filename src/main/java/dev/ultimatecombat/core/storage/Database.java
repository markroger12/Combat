package dev.ultimatecombat.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection pool and asynchronous executor for every persistent feature.
 *
 * <p>All queries run on a dedicated executor (never on the main or a region thread). SQLite uses
 * a single connection and a single writer thread to avoid {@code SQLITE_BUSY}; MySQL/MariaDB use
 * a configurable pool.</p>
 */
public final class Database {

    public enum Type { SQLITE, MYSQL, MARIADB }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    private final Logger logger;
    private volatile HikariDataSource dataSource;
    private volatile ExecutorService executor;
    private volatile StorageSettings settings;
    private volatile boolean available;

    public Database(Logger logger) {
        this.logger = logger;
    }

    public void start(StorageSettings settings, File dataFolder) throws SQLException {
        this.settings = settings;
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("UltimateCombat-Pool");
        switch (settings.type()) {
            case SQLITE -> {
                File file = new File(dataFolder, settings.sqliteFile());
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new SQLException("Cannot create folder " + parent);
                }
                cfg.setDriverClassName(firstAvailable("org.sqlite.JDBC"));
                cfg.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
                cfg.setMaximumPoolSize(1);
                cfg.setMinimumIdle(1);
                cfg.setConnectionInitSql("PRAGMA busy_timeout = 10000");
                cfg.addDataSourceProperty("journal_mode", "WAL");
                cfg.addDataSourceProperty("synchronous", "NORMAL");
                cfg.addDataSourceProperty("foreign_keys", "true");
            }
            case MYSQL, MARIADB -> {
                String driver = settings.type() == Type.MARIADB
                        ? firstAvailable("org.mariadb.jdbc.Driver", "com.mysql.cj.jdbc.Driver")
                        : firstAvailable("com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver");
                cfg.setDriverClassName(driver);
                String scheme = driver.startsWith("org.mariadb") ? "mariadb" : "mysql";
                StringBuilder url = new StringBuilder("jdbc:").append(scheme).append("://")
                        .append(settings.host()).append(':').append(settings.port()).append('/').append(settings.database())
                        .append("?useSSL=").append(settings.useSsl())
                        .append("&characterEncoding=utf8");
                settings.properties().forEach((k, v) -> url.append('&').append(k).append('=').append(v));
                cfg.setJdbcUrl(url.toString());
                cfg.setUsername(settings.username());
                cfg.setPassword(settings.password());
                cfg.setMaximumPoolSize(settings.poolSize());
                cfg.setMinimumIdle(Math.min(settings.minimumIdle(), settings.poolSize()));
                cfg.addDataSourceProperty("cachePrepStmts", "true");
                cfg.addDataSourceProperty("prepStmtCacheSize", "250");
                cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                cfg.addDataSourceProperty("useServerPrepStmts", "true");
            }
        }
        cfg.setConnectionTimeout(settings.connectionTimeoutMillis());
        cfg.setMaxLifetime(settings.maxLifetimeMillis());
        if (settings.keepaliveMillis() > 0) {
            cfg.setKeepaliveTime(settings.keepaliveMillis());
        }
        this.dataSource = new HikariDataSource(cfg);
        int threads = settings.type() == Type.SQLITE ? 1 : Math.max(1, settings.threads());
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "UltimateCombat-DB-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(threads, factory);
        if (settings.type() == Type.SQLITE) {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
        }
        this.available = true;
    }

    private static String firstAvailable(String... drivers) throws SQLException {
        for (String d : drivers) {
            try {
                Class.forName(d);
                return d;
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        throw new SQLException("No JDBC driver found (tried " + String.join(", ", drivers) + ")");
    }

    public Type type() {
        return settings.type();
    }

    public StorageSettings settings() {
        return settings;
    }

    public boolean isAvailable() {
        return available && dataSource != null && !dataSource.isClosed();
    }

    /**
     * @return the table name with the configured prefix
     */
    public String t(String table) {
        return settings.tablePrefix() + table;
    }

    public String group() {
        return settings.dataGroup();
    }

    public <T> CompletableFuture<T> supply(SqlFunction<T> function) {
        ExecutorService ex = executor;
        if (ex == null || ex.isShutdown()) {
            return CompletableFuture.failedFuture(new SQLException("Database is not running"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection()) {
                return function.apply(c);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, ex).whenComplete((r, t) -> {
            if (t != null) {
                logger.log(Level.WARNING, "Database operation failed: " + rootMessage(t));
            }
        });
    }

    public CompletableFuture<Void> run(SqlConsumer consumer) {
        return supply(c -> {
            consumer.accept(c);
            return null;
        });
    }

    /**
     * Runs a transaction; rolls back on any failure.
     */
    public <T> CompletableFuture<T> transaction(SqlFunction<T> function) {
        return supply(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = function.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /**
     * Blocking variant for startup, shutdown and AsyncPlayerPreLoginEvent (already async).
     * Must never be called from the main or a region thread while the server is running.
     */
    public <T> T supplySync(SqlFunction<T> function) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return function.apply(c);
        }
    }

    public static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /**
     * Finishes queued writes (bounded wait) and closes the pool.
     */
    public void shutdown(long timeoutMillis) {
        available = false;
        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    logger.warning("Database tasks did not finish within " + timeoutMillis + "ms; forcing shutdown");
                    ex.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.shutdownNow();
            }
        }
        HikariDataSource ds = dataSource;
        if (ds != null) {
            ds.close();
        }
    }

    // ------------------------------------------------------------------ dialect helpers

    public String autoIncrementPk() {
        return type() == Type.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
    }

    public String longText() {
        return type() == Type.SQLITE ? "TEXT" : "LONGTEXT";
    }

    public String greatest() {
        return type() == Type.SQLITE ? "MAX" : "GREATEST";
    }

    /**
     * Builds an upsert clause. {@code updates} are SQL assignments using {@code NEW(col)} as
     * placeholder for the inserted value, for example {@code kills = kills + NEW(kills)}.
     */
    public String upsert(String table, String[] columns, String[] keys, String[] updates) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (")
                .append(String.join(", ", columns)).append(") VALUES (")
                .append("?, ".repeat(columns.length - 1)).append("?)");
        if (type() == Type.SQLITE) {
            sb.append(" ON CONFLICT(").append(String.join(", ", keys)).append(") DO UPDATE SET ");
        } else {
            sb.append(" ON DUPLICATE KEY UPDATE ");
        }
        for (int i = 0; i < updates.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(newValue(updates[i]));
        }
        return sb.toString();
    }

    private String newValue(String expr) {
        StringBuilder out = new StringBuilder();
        int idx = 0;
        while (true) {
            int start = expr.indexOf("NEW(", idx);
            if (start < 0) {
                out.append(expr.substring(idx));
                break;
            }
            int end = expr.indexOf(')', start);
            String col = expr.substring(start + 4, end);
            out.append(expr, idx, start);
            out.append(type() == Type.SQLITE ? "excluded." + col : "VALUES(" + col + ")");
            idx = end + 1;
        }
        return out.toString();
    }
}
