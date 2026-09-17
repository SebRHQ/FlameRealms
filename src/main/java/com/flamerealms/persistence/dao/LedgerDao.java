package com.flamerealms.persistence.dao;

import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.domain.TransactionRecord;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The single, shared choke point for every balance mutation in the economy.
 *
 * <p><b>No code anywhere in this project may update
 * {@code player_wallets.balance_cents} or {@code realms.balance_cents}
 * without going through one of this class's {@code recordAndApply*} methods,
 * in the same transaction as the ledger insert. A balance change with no
 * corresponding {@code transactions} row is a bug, not an acceptable
 * shortcut.</b> No future stage may bypass this by writing its own ad-hoc
 * balance {@code UPDATE} plus a separate ledger {@code INSERT} — the two
 * must always be composed here, on the same {@link Connection}, so a caller
 * that wraps its call in a transaction (see {@code RealmServiceImpl#
 * inTransaction} for the established pattern) gets "balance mutation and
 * audit entry commit together, or neither happens" for free.
 *
 * <p><b>Direction convention.</b> For both {@code recordAndApply*} methods,
 * {@code deltaCents} is signed from the named party's (the player's or the
 * realm's) point of view: positive means money flows into that party,
 * negative means money flows out of it. The inserted {@link TransactionRecord}
 * always stores a non-negative {@code amountCents} (the absolute value of
 * {@code deltaCents}) and encodes direction purely through
 * {@code sourceType}/{@code sourceId} and {@code targetType}/{@code targetId}:
 * a positive delta makes the named party the {@code target} and the supplied
 * counterparty the {@code source}; a negative delta makes the named party the
 * {@code source} and the counterparty the {@code target}. A zero delta is
 * treated as incoming (named party as {@code target}) purely by convention,
 * since there is no direction to infer from a no-op amount.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — see
 * {@link RealmDao} for the composition contract this DAO shares with it.
 */
public interface LedgerDao {

    /**
     * Inserts {@code record} as a new row in the append-only
     * {@code transactions} audit log and returns its generated id.
     * {@code record.id()} is ignored on the way in. This is a plain insert
     * with no balance side effect — callers that need both go through
     * {@link #recordAndApplyToPlayer} or {@link #recordAndApplyToRealm}
     * instead.
     */
    long insertEntry(Connection connection, TransactionRecord record) throws SQLException;

    /**
     * Ensures {@code player}'s wallet exists, then atomically attempts to
     * apply {@code deltaCents} to its balance and, only if that succeeds,
     * inserts exactly one {@link TransactionRecord} for it in the same
     * connection — see the class Javadoc's direction convention for how
     * {@code counterpartyType}/{@code counterpartyId} map onto the inserted
     * row's source/target.
     *
     * @return {@code true} if the balance was adjusted and the ledger entry
     *         inserted; {@code false} if the adjustment would have taken the
     *         wallet negative, in which case NOTHING was written — no
     *         balance change and no {@code transactions} row. The caller is
     *         expected to roll back the surrounding transaction in that case.
     */
    boolean recordAndApplyToPlayer(
            Connection connection,
            UUID player,
            long deltaCents,
            TransactionCategory category,
            String reason,
            LedgerEntity counterpartyType,
            String counterpartyId
    ) throws SQLException;

    /**
     * Identical in shape to {@link #recordAndApplyToPlayer}, but against a
     * realm's treasury ({@code realms.balance_cents} via
     * {@link RealmDao#tryAdjustBalance}) rather than a player's wallet.
     *
     * @return {@code true} if the balance was adjusted and the ledger entry
     *         inserted; {@code false} if the adjustment would have taken the
     *         treasury negative, in which case NOTHING was written.
     */
    boolean recordAndApplyToRealm(
            Connection connection,
            long realmId,
            long deltaCents,
            TransactionCategory category,
            String reason,
            LedgerEntity counterpartyType,
            String counterpartyId
    ) throws SQLException;
}
