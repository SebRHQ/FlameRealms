package com.flamerealms.service.exception;

import java.sql.SQLException;

/**
 * Wraps an unexpected {@link SQLException} surfaced from a DAO call inside a
 * {@code RealmServiceImpl} transaction. Every DAO method in this project
 * declares {@code throws SQLException} (checked), but
 * {@code AsyncDatabaseExecutor.submit}'s {@code Function<Connection, T>}
 * cannot declare checked exceptions — so {@code RealmServiceImpl} catches
 * {@link SQLException} at the boundary of its {@code submit(...)} lambda and
 * rethrows it wrapped here, unchecked, so it can still complete the
 * returned {@code CompletableFuture} exceptionally.
 *
 * <p>Constraint-violation races that have a clearer domain meaning (a realm
 * name or a player's one-realm-at-a-time invariant) are translated into
 * {@link RealmNameTakenException} / {@link PlayerAlreadyInRealmException}
 * instead, before they would otherwise reach this generic wrapper.
 */
public final class RealmPersistenceException extends RealmServiceException {

    public RealmPersistenceException(String message, SQLException cause) {
        super(message, cause);
    }
}
