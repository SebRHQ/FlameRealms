package com.flamerealms.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * The sole gateway to the database for the entire FlameRealms plugin.
 *
 * <p><b>No DAO or service method may open a JDBC connection or run a query on
 * any thread other than one supplied by this executor.</b> If you are tempted
 * to call a DAO method directly from a Bukkit event handler or command
 * executor, you are doing it wrong — dispatch through
 * {@link #submit(Function)} and hop back to the main thread via
 * {@code Bukkit.getScheduler().runTask(...)} to apply the result.
 *
 * <p>The Paper main thread must never block on a database call. This class is
 * the boundary that enforces that: every transaction, from this point
 * forward, for the rest of the project, runs on a thread owned by this
 * executor's bounded pool, never on the caller's thread.
 */
public final class AsyncDatabaseExecutor {

    private final DataSource dataSource;
    private final ExecutorService executor;
    private final boolean available;

    /**
     * @param databaseManager the manager whose pooled {@link DataSource} this
     *                         executor draws connections from
     * @param poolSize         number of threads in the fixed thread pool that
     *                          runs all database work; sourced from
     *                          {@code DatabaseConfig.asyncPoolSize()}
     */
    public AsyncDatabaseExecutor(DatabaseManager databaseManager, int poolSize) {
        this.dataSource = databaseManager.dataSource();
        this.executor = Executors.newFixedThreadPool(poolSize, AsyncDatabaseExecutor::newDatabaseThread);
        this.available = true;
    }

    private AsyncDatabaseExecutor() {
        this.dataSource = null;
        this.executor = null;
        this.available = false;
    }

    /**
     * Builds an executor with no real {@link DataSource} or thread pool at
     * all — used when {@code DatabaseManager} could not be constructed
     * during {@code onEnable()} (HikariCP could not connect, or Flyway could
     * not migrate). Every {@link #submit(Function)} call on the returned
     * instance fails immediately with a {@link DatabaseUnavailableException},
     * and {@link #shutdown(Duration)} is a safe no-op.
     */
    public static AsyncDatabaseExecutor unavailable() {
        return new AsyncDatabaseExecutor();
    }

    private static Thread newDatabaseThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "FlameRealms-DB-Worker");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Runs {@code work} on this executor's thread pool, supplying it a
     * {@link Connection} borrowed from the pool. The connection is returned
     * (closed back to the pool) whether {@code work} succeeds or throws.
     *
     * <p>Never runs on the calling thread — including the main thread. Callers
     * on the main thread must apply the result via
     * {@code Bukkit.getScheduler().runTask(...)} in a {@code thenAccept}/{@code
     * whenComplete} continuation, never by blocking on the returned future.
     *
     * @param work a function that receives a live {@link Connection} and
     *             returns the transaction's result; any {@link SQLException}
     *             it throws (checked or wrapped) completes the returned
     *             future exceptionally
     */
    public <T> CompletableFuture<T> submit(Function<Connection, T> work) {
        if (!available) {
            return CompletableFuture.failedFuture(new DatabaseUnavailableException(
                    "The database is unavailable — FlameRealms is running in degraded mode "
                            + "because it could not connect or migrate at startup."));
        }

        CompletableFuture<T> future = new CompletableFuture<>();

        try {
            executor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    future.complete(work.apply(connection));
                } catch (SQLException e) {
                    future.completeExceptionally(e);
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            // executor.submit() itself throws this synchronously — not from
            // inside the task above — once shutdown(...) has been called
            // (e.g. during this plugin's onDisable()). A caller can still
            // reach this method after that point (the Vault economy bridge
            // stays registered with Bukkit's ServicesManager and reachable
            // by other plugins during their own onDisable()), so fail the
            // future exceptionally here too rather than letting the
            // exception escape synchronously and bypass every call site's
            // uniform .exceptionally(...) handling.
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Stops accepting new work and awaits termination of in-flight tasks up
     * to {@code timeout}. Intended to be called from a graceful
     * {@code onDisable()}, before {@code DatabaseManager.shutdown()} closes
     * the underlying pool, so no in-flight task is left reaching for a
     * connection that no longer exists.
     */
    public void shutdown(Duration timeout) {
        if (!available) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
