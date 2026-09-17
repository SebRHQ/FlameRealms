package com.flamerealms.persistence;

/**
 * Thrown to fail a {@link java.util.concurrent.CompletableFuture} returned by
 * {@link AsyncDatabaseExecutor#submit(java.util.function.Function)} when the
 * database was never reachable in the first place — i.e. {@code
 * AsyncDatabaseExecutor} is running in {@link AsyncDatabaseExecutor#unavailable()}
 * mode because {@code DatabaseManager} could not be constructed during {@code
 * onEnable()} (HikariCP could not connect, or Flyway could not migrate).
 *
 * <p>This is distinct from a normal {@link java.sql.SQLException} surfaced by
 * a healthy pool at call time — it means there never was a pool to begin
 * with, so every database-dependent command should map it to one clear,
 * specific in-game message rather than the generic persistence-error one.
 */
public final class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException(String message) {
        super(message);
    }
}
