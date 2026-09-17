package com.flamerealms.service;

import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Money;
import com.flamerealms.domain.PlayerWallet;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.PlayerWalletDao;
import com.flamerealms.service.exception.EconomyPersistenceException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link EconomyService} implementation.
 *
 * <p><b>Transaction boundaries.</b> Exactly like {@code RealmServiceImpl},
 * {@code AsyncDatabaseExecutor.submit(...)} hands out a connection in
 * whatever auto-commit state HikariCP configured and does no transaction
 * management of its own. {@code LedgerDao.recordAndApplyToPlayer} composes
 * three separate statements ({@code PlayerWalletDao.ensureExists}, {@code
 * PlayerWalletDao.tryAdjustBalance}, {@code LedgerDao.insertEntry}) on one
 * connection without demarcating a transaction itself (see its class
 * Javadoc) — so {@link #deposit} and {@link #withdraw} both wrap their single
 * {@code recordAndApplyToPlayer} call in {@link #inTransaction} here, even
 * though it is only one DAO call, to guarantee those three statements commit
 * or roll back together rather than auto-committing individually.
 *
 * <p>Both {@link #deposit} and {@link #withdraw} simply return whatever
 * {@code recordAndApplyToPlayer} returns: {@code true} unless the adjustment
 * would have taken the wallet negative. For {@link #deposit} (a positive
 * delta) that condition is essentially unreachable — crediting money cannot
 * make a balance more negative — so in practice it always succeeds; the
 * only way {@link #deposit} fails is a genuine {@link SQLException}, which
 * is wrapped into {@link EconomyPersistenceException} and propagates through
 * the future exceptionally, exactly like every other unexpected DB failure
 * in this project (see {@code RealmPersistenceException}). For
 * {@link #withdraw} (a negative delta), a {@code false} return is the
 * ordinary "insufficient funds" outcome.
 */
public final class EconomyServiceImpl implements EconomyService {

    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final PlayerWalletDao playerWalletDao;
    private final LedgerDao ledgerDao;

    public EconomyServiceImpl(
            AsyncDatabaseExecutor asyncDatabaseExecutor,
            PlayerWalletDao playerWalletDao,
            LedgerDao ledgerDao
    ) {
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.playerWalletDao = playerWalletDao;
        this.ledgerDao = ledgerDao;
    }

    @Override
    public CompletableFuture<Money> balanceOf(UUID player) {
        return asyncDatabaseExecutor.submit(connection -> {
            try {
                Optional<PlayerWallet> wallet = playerWalletDao.findByPlayer(connection, player);
                return Money.ofCents(wallet.map(PlayerWallet::balanceCents).orElse(0L));
            } catch (SQLException e) {
                throw new EconomyPersistenceException(
                        "Failed to read wallet balance for player " + player, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID player, Money amount, String reason) {
        requirePositive(amount);

        return asyncDatabaseExecutor.<Boolean>submit(connection -> {
            try {
                return inTransaction(connection, conn -> ledgerDao.recordAndApplyToPlayer(
                        conn, player, amount.cents(), TransactionCategory.FAUCET, reason,
                        LedgerEntity.SERVER, null));
            } catch (SQLException e) {
                throw new EconomyPersistenceException(
                        "Failed to deposit " + amount + " to player " + player, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID player, Money amount, String reason) {
        requirePositive(amount);

        return asyncDatabaseExecutor.<Boolean>submit(connection -> {
            try {
                return inTransaction(connection, conn -> ledgerDao.recordAndApplyToPlayer(
                        conn, player, -amount.cents(), TransactionCategory.SINK, reason,
                        LedgerEntity.SERVER, null));
            } catch (SQLException e) {
                throw new EconomyPersistenceException(
                        "Failed to withdraw " + amount + " from player " + player, e);
            }
        });
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
    }

    /**
     * Runs {@code work} inside an explicit transaction on {@code connection}
     * — {@code setAutoCommit(false)}, then either {@code commit()} once
     * {@code work} returns or {@code rollback()} if it throws, always
     * restoring the connection's original auto-commit state before
     * returning. Identical in shape to {@code RealmServiceImpl}'s private
     * helper of the same name; not shared between the two classes yet (see
     * that class's Javadoc for why a shared helper is a reasonable future
     * follow-up but out of scope here).
     */
    private static <T> T inTransaction(Connection connection, SqlWork<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
