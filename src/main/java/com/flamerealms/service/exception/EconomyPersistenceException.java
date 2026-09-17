package com.flamerealms.service.exception;

import java.sql.SQLException;

/**
 * Wraps an unexpected {@link SQLException} surfaced from a DAO call inside an
 * {@code EconomyServiceImpl} or {@code TreasuryServiceImpl} transaction —
 * the economy-layer analogue of {@link RealmPersistenceException}. See that
 * class's Javadoc for why this exists: every DAO method declares
 * {@code throws SQLException}, but {@code AsyncDatabaseExecutor.submit}'s
 * {@code Function<Connection, T>} cannot declare checked exceptions, so the
 * service impl catches it at the boundary of its {@code submit(...)} lambda
 * and rethrows it wrapped here, unchecked, so it can still complete the
 * returned {@code CompletableFuture} exceptionally.
 *
 * <p>This is reserved for genuinely unexpected failures. The two ordinary,
 * expected outcomes of an economy operation — insufficient player or realm
 * funds — are never routed through this class; they complete their future
 * normally with {@code false}, per {@code EconomyService} and
 * {@code TreasuryService}'s own contracts.
 */
public final class EconomyPersistenceException extends EconomyServiceException {

    public EconomyPersistenceException(String message, SQLException cause) {
        super(message, cause);
    }
}
