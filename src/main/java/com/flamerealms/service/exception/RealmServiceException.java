package com.flamerealms.service.exception;

/**
 * Base type for every domain-level failure {@code RealmService} can raise —
 * either directly (a rule the service itself enforces) or by translating an
 * underlying {@link java.sql.SQLException} into something the command layer
 * (or any other caller) can branch on without ever seeing raw JDBC types.
 *
 * <p>Unchecked, deliberately: these travel through
 * {@code CompletableFuture.completeExceptionally(...)} and are inspected in
 * a {@code whenComplete}/{@code exceptionally} continuation back on the main
 * thread, not caught synchronously by the caller.
 */
public abstract class RealmServiceException extends RuntimeException {

    protected RealmServiceException(String message) {
        super(message);
    }

    protected RealmServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
