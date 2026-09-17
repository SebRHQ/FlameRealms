package com.flamerealms.domain;

/**
 * The kind of party on one side of a ledger transaction
 * ({@code transactions.source_type} / {@code transactions.target_type}).
 *
 * <p>Matches the {@code ENUM('PLAYER','REALM','SERVER')} columns in the
 * {@code transactions} table exactly — the names must stay in sync.
 */
public enum LedgerEntity {
    /** A single player, identified by their UUID. */
    PLAYER,
    /** A realm, identified by its id. */
    REALM,
    /** The server itself, with no further identifier. */
    SERVER
}
