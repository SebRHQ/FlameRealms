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
 * @param poolSize         HikariCP maximum pool size (config: {@code database.pool-size})
 * @param asyncPoolSize    size of the async database executor's fixed thread pool
 *                         (config: {@code async.executor-thread-pool-size})
 */
public record DatabaseConfig(
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize,
        int asyncPoolSize
) {

    private static final int DEFAULT_ASYNC_POOL_SIZE = 4;

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

        return new DatabaseConfig(host, port, database, username, password, poolSize, asyncPoolSize);
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
