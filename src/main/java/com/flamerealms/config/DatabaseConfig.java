package com.flamerealms.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Immutable binding of config.yml's {@code database:} and {@code async:} blocks.
 *
 * <p>This record only carries plain data — no connections, no pools. It is
 * read once from a {@link FileConfiguration} during plugin startup and handed
 * to {@code DatabaseManager} and {@code AsyncDatabaseExecutor} to construct
 * themselves from.
 *
 * @param host             database host (config: {@code database.host})
 * @param port             database port (config: {@code database.port})
 * @param database         database/schema name (config: {@code database.database})
 * @param username         database username (config: {@code database.username})
 * @param password         database password (config: {@code database.password})
 * @param poolSize             HikariCP maximum pool size (config: {@code database.pool-size})
 * @param asyncPoolSize        size of the async database executor's fixed thread pool
 *                             (config: {@code async.executor-thread-pool-size})
 * @param connectionTimeoutMs  HikariCP connection timeout, in milliseconds
 *                             (config: {@code database.connection-timeout-ms})
 * @param minimumIdle          HikariCP minimum idle connections
 *                             (config: {@code database.minimum-idle})
 * @param maxLifetimeMs        HikariCP maximum connection lifetime, in milliseconds
 *                             (config: {@code database.max-lifetime-ms})
 * @param validationTimeoutMs  HikariCP validation timeout, in milliseconds
 *                             (config: {@code database.validation-timeout-ms})
 */
public record DatabaseConfig(
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize,
        int asyncPoolSize,
        long connectionTimeoutMs,
        int minimumIdle,
        long maxLifetimeMs,
        long validationTimeoutMs
) {

    private static final int DEFAULT_ASYNC_POOL_SIZE = 4;
    // Each of these matches HikariCP's own documented default, so leaving
    // the corresponding config.yml key unset changes nothing.
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;
    private static final long DEFAULT_VALIDATION_TIMEOUT_MS = 5_000L;

    /**
     * Reads the {@code database:} and {@code async:} blocks from the given
     * configuration (typically {@code plugin.getConfig()} after
     * {@code saveDefaultConfig()} has been called).
     *
     * @param logger used to warn about a present-but-wrong-typed value (see
     *               {@link #getIntStrict}) and is otherwise not retained
     * @throws IllegalArgumentException if {@code database.pool-size} or
     *                                   {@code async.executor-thread-pool-size}
     *                                   resolves to a non-positive value —
     *                                   caught here, with the offending key
     *                                   named, rather than surfacing later as
     *                                   a message-less {@code
     *                                   IllegalArgumentException} out of
     *                                   {@code ThreadPoolExecutor}'s
     *                                   constructor
     */
    public static DatabaseConfig fromConfig(FileConfiguration config, Logger logger) {
        String host = config.getString("database.host", "localhost");
        int port = getIntStrict(config, "database.port", 3306, logger);
        String database = config.getString("database.database", "flamerealms");
        String username = config.getString("database.username", "flamerealms");
        String password = config.getString("database.password", "");
        int poolSize = requirePositive(
                "database.pool-size", getIntStrict(config, "database.pool-size", 10, logger));
        int asyncPoolSize = requirePositive(
                "async.executor-thread-pool-size",
                getIntStrict(config, "async.executor-thread-pool-size", DEFAULT_ASYNC_POOL_SIZE, logger));

        long connectionTimeoutMs = getLongStrict(
                config, "database.connection-timeout-ms", DEFAULT_CONNECTION_TIMEOUT_MS, logger);
        // HikariCP's own default is minimumIdle == maximumPoolSize unless
        // overridden, so mirror poolSize here rather than a fixed constant.
        int minimumIdle = getIntStrict(config, "database.minimum-idle", poolSize, logger);
        long maxLifetimeMs = getLongStrict(config, "database.max-lifetime-ms", DEFAULT_MAX_LIFETIME_MS, logger);
        long validationTimeoutMs = getLongStrict(
                config, "database.validation-timeout-ms", DEFAULT_VALIDATION_TIMEOUT_MS, logger);

        return new DatabaseConfig(
                host, port, database, username, password, poolSize, asyncPoolSize,
                connectionTimeoutMs, minimumIdle, maxLifetimeMs, validationTimeoutMs);
    }

    /**
     * Like {@link FileConfiguration#getInt(String, int)}, except it warns
     * instead of silently falling back to {@code def} when {@code path} is
     * actually set to a value of the wrong YAML type (e.g. a quoted {@code
     * port: "3307"} parses as a String, which {@code getInt} cannot coerce
     * and would otherwise discard without a trace). A path that is simply
     * unset is not warned about — that is the normal, intended way to accept
     * the default.
     */
    private static int getIntStrict(FileConfiguration config, String path, int def, Logger logger) {
        Object raw = config.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        logger.warning("config.yml: '" + path + "' is set to '" + raw
                + "' (not a number) — falling back to the default of " + def
                + ". Check for a stray quote around the value.");
        return def;
    }

    /**
     * Like {@link #getIntStrict}, but for the long-valued HikariCP tuning
     * knobs (timeouts/lifetimes, given in milliseconds). Same logic: warns
     * on a present-but-wrong-typed value, stays silent when the path is
     * simply unset.
     */
    private static long getLongStrict(FileConfiguration config, String path, long def, Logger logger) {
        Object raw = config.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        logger.warning("config.yml: '" + path + "' is set to '" + raw
                + "' (not a number) — falling back to the default of " + def
                + ". Check for a stray quote around the value.");
        return def;
    }

    /**
     * Guards a pool-size config value that flows straight into {@code
     * Executors.newFixedThreadPool}/HikariCP, both of which reject a
     * non-positive size — HikariCP names the field in its own exception, but
     * {@code ThreadPoolExecutor}'s constructor throws a bare, message-less
     * {@code IllegalArgumentException}. Failing fast here, with the
     * offending config key named, avoids that undiagnosable startup crash.
     */
    private static int requirePositive(String path, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "config.yml: '" + path + "' must be positive, but was " + value);
        }
        return value;
    }

    /**
     * Builds the MariaDB JDBC URL for this configuration.
     */
    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database;
    }
}
