package com.flamerealms.persistence.dao;

import com.flamerealms.domain.PlayerWallet;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code player_wallets} table.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — see
 * {@link RealmDao} for the composition contract this DAO shares with it.
 *
 * <p>Nothing here inserts a ledger row. Callers that mutate a balance and
 * need an audit trail entry for it go through {@code LedgerDao} instead,
 * which composes {@link #ensureExists} and {@link #tryAdjustBalance} with a
 * {@code transactions} insert in one connection. See {@code LedgerDao}'s
 * class Javadoc.
 */
public interface PlayerWalletDao {

    /** Looks up a player's wallet. Empty if the player has no wallet row yet. */
    Optional<PlayerWallet> findByPlayer(Connection connection, UUID playerUuid) throws SQLException;

    /**
     * Ensures a zero-balance wallet row exists for {@code playerUuid},
     * inserting one if absent. A no-op if the player already has a wallet
     * row — this is what lets a player's very first economy interaction
     * skip any "do they have a wallet yet?" special case elsewhere.
     */
    void ensureExists(Connection connection, UUID playerUuid) throws SQLException;

    /**
     * Conditionally applies {@code deltaCents} to the player's balance in a
     * single {@code UPDATE ... WHERE balance_cents + ? >= 0}, never by
     * reading the balance and writing it back. Returns {@code true} only if
     * a row was actually affected, i.e. the wallet exists and the resulting
     * balance would not go negative; returns {@code false} otherwise, in
     * which case the balance was left completely untouched.
     */
    boolean tryAdjustBalance(Connection connection, UUID playerUuid, long deltaCents) throws SQLException;
}
