package com.flamerealms.service.exception;

/**
 * Base type for every domain-level failure {@code EconomyService} or
 * {@code TreasuryService} can raise — mirrors {@link RealmServiceException}'s
 * role for {@code RealmService}, but kept as its own hierarchy since the
 * economy layer (player wallets, realm treasuries, the ledger) is a distinct
 * concern from realm/membership management.
 *
 * <p>Unchecked, deliberately: these travel through
 * {@code CompletableFuture.completeExceptionally(...)} and are inspected in
 * a {@code whenComplete}/{@code exceptionally} continuation back on the main
 * thread, not caught synchronously by the caller.
 */
public abstract class EconomyServiceException extends RuntimeException {

    protected EconomyServiceException(String message) {
        super(message);
    }

    protected EconomyServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
