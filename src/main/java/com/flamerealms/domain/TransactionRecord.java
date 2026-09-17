package com.flamerealms.domain;

import java.time.Instant;

/**
 * A single row of the append-only {@code transactions} audit log.
 *
 * <p>{@code transactions} is an audit trail, never the source of truth for a
 * live balance — balances always live on {@code realms.balance_cents} /
 * {@code player_wallets.balance_cents} and are read directly, never derived
 * by summing this table.
 *
 * @param id          surrogate primary key ({@code transactions.id}); {@code 0}
 *                    or unset before the row has been inserted
 * @param ts          when the transaction occurred ({@code transactions.ts})
 * @param category    the broad shape of the transaction
 *                    ({@code transactions.category})
 * @param reason      a short application-defined reason code
 *                    ({@code transactions.reason})
 * @param sourceType  the kind of party money moved from
 *                    ({@code transactions.source_type})
 * @param sourceId    identifier of the source party, {@code null} when not
 *                    applicable (e.g. {@link LedgerEntity#SERVER})
 *                    ({@code transactions.source_id})
 * @param targetType  the kind of party money moved to, {@code null} when not
 *                    applicable ({@code transactions.target_type})
 * @param targetId    identifier of the target party, {@code null} when not
 *                    applicable ({@code transactions.target_id})
 * @param amountCents the transaction amount, in whole cents
 *                    ({@code transactions.amount_cents})
 * @param metadata    raw JSON string of extra context, {@code null} when
 *                    absent ({@code transactions.metadata})
 */
public record TransactionRecord(
        long id,
        Instant ts,
        TransactionCategory category,
        String reason,
        LedgerEntity sourceType,
        String sourceId,
        LedgerEntity targetType,
        String targetId,
        long amountCents,
        String metadata
) {
}
