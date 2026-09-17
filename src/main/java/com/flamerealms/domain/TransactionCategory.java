package com.flamerealms.domain;

/**
 * The broad shape of a ledger transaction ({@code transactions.category}).
 *
 * <p>Matches the {@code ENUM('FAUCET','SINK','TRANSFER')} column in the
 * {@code transactions} table exactly — the names must stay in sync.
 */
public enum TransactionCategory {
    /** Money created from nothing (e.g. a reward or admin grant). */
    FAUCET,
    /** Money destroyed / removed from the economy (e.g. a fee or upkeep charge). */
    SINK,
    /** Money moved between two existing ledger entities. */
    TRANSFER
}
