package com.flamerealms.persistence;

import com.flamerealms.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Owns the HikariCP connection pool and runs schema migrations for
 * FlameRealms.
 *
 * <p>Construction runs Flyway migrations synchronously against
 * {@code classpath:db/migration}. That synchronous call is only ever made
 * once, during plugin startup ({@code onEnable()}) — it is a deliberate
 * exception to the "never block the main thread" rule, acceptable because it
 * happens before the server is serving players, not during normal gameplay
 * ticks. Every other use of this manager's {@link #dataSource()} must go
 * through {@link AsyncDatabaseExecutor}.
 */
public final class DatabaseManager {

    private final HikariDataSource dataSource;

    /**
     * Builds the HikariCP pool from {@code config} and immediately runs
     * Flyway migrations against it. Intended to be called once, synchronously,
     * from {@code onEnable()}.
     */
    public DatabaseManager(DatabaseConfig config) {
        this.dataSource = buildDataSource(config);
        try {
            migrate();
        } catch (RuntimeException e) {
            // buildDataSource(...) already eagerly opened a live HikariCP pool
            // (real connections + its background housekeeper thread) as a side
            // effect of construction, before migrate() ever ran. If migration
            // then fails, this constructor throws and never returns an
            // instance — so FlameRealmsPlugin's degraded-mode catch block has
            // no DatabaseManager reference to call shutdown() on. Without this,
            // that fully-live pool would leak for the rest of the JVM's life.
            dataSource.close();
            throw e;
        }
    }

    private static HikariDataSource buildDataSource(DatabaseConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.poolSize());
        hikariConfig.setConnectionTimeout(config.connectionTimeoutMs());
        hikariConfig.setMinimumIdle(config.minimumIdle());
        hikariConfig.setMaxLifetime(config.maxLifetimeMs());
        hikariConfig.setValidationTimeout(config.validationTimeoutMs());
        hikariConfig.setPoolName("FlameRealms-Hikari");
        // Must be set explicitly: Bukkit/Paper loads each plugin with its own
        // classloader, so java.sql.DriverManager's ServiceLoader-based lookup
        // (keyed off the calling thread's context classloader) cannot see the
        // driver shaded into this jar. Naming the class here makes HikariCP
        // load it directly instead of going through DriverManager.
        hikariConfig.setDriverClassName("com.flamerealms.libs.mariadb.Driver");

        return new HikariDataSource(hikariConfig);
    }

    private void migrate() {
        // Flyway.configure() with no argument scans the calling thread's
        // context classloader for "classpath:db/migration" — on the Bukkit
        // main thread that is the server's own classloader, not this
        // plugin's, so it silently finds zero migrations. Passing this
        // class's own classloader makes it scan the plugin jar itself.
        Flyway flyway = Flyway.configure(DatabaseManager.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();

        flyway.migrate();
    }

    /**
     * The underlying pooled {@link DataSource}. Only {@link AsyncDatabaseExecutor}
     * should draw connections from this — see its class Javadoc.
     */
    DataSource dataSource() {
        return dataSource;
    }

    /**
     * Closes the HikariCP pool. Must be called only after
     * {@link AsyncDatabaseExecutor#shutdown(java.time.Duration)} has drained
     * all in-flight work, so no thread is left holding or requesting a
     * connection from a closed pool.
     */
    public void shutdown() {
        dataSource.close();
    }
}
