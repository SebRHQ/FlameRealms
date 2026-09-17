package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Money;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.RealmDao;
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.persistence.dao.RealmRankDao;
import com.flamerealms.service.exception.EconomyPersistenceException;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.exception.PlayerNotInRealmException;
import com.flamerealms.service.exception.RealmNotFoundException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link TreasuryService} implementation.
 *
 * <p><b>Realm existence.</b> Every method fast-fails against {@link
 * RealmCache} — the same synchronous pre-check {@code RealmServiceImpl} uses
 * elsewhere — before ever dispatching to {@link AsyncDatabaseExecutor}, so an
 * unknown {@code realmId} never reaches the database at all.
 *
 * <p><b>The {@code WITHDRAW} permission check has no cache to answer it
 * synchronously.</b> {@link RealmCache} deliberately caches only {@code id ->
 * Realm}, {@code name -> id} and {@code player -> realmId} (no rank/permission
 * data — see its own class Javadoc), and neither {@link RealmService} nor any
 * other M1 type exposes a synchronous "does this player hold this permission
 * in this realm" read. So {@link #withdraw}'s permission check necessarily
 * reads {@code realm_members} and {@code realm_ranks} through {@link
 * RealmMemberDao}/{@link RealmRankDao} inside the same database dispatch as
 * the transfer itself — exactly the pattern {@code RealmServiceImpl#setRank}
 * already uses for its own {@code MANAGE_RANKS} check. What "without
 * touching the database at all" (per this class's Javadoc) buys is narrower
 * but just as real: the check runs as the very first thing inside the
 * transaction, strictly before either balance is touched or any ledger row
 * is inserted, so a failed check never mutates {@code realms},
 * {@code player_wallets} or {@code transactions} — only a read happens.
 *
 * <p><b>Lock ordering vs. transfer direction.</b> {@link TreasuryService}'s
 * class Javadoc fixes the lock order as player-wallet-then-realm-treasury for
 * every method here, specifically so two concurrent transfers against the
 * same player+realm pair can never acquire those two row locks in opposite
 * orders. That means {@link #withdraw} (money conceptually flowing realm -&gt;
 * player) still adjusts the player's wallet (a credit) before the realm's
 * treasury (a debit) inside its transaction — the reverse of the literal
 * "debit the realm, then credit the player" phrasing a first read of the
 * requirement suggests, and a deliberate resolution of that tension in favor
 * of the fixed, project-wide lock-ordering invariant. This does not change
 * the operation's atomicity or its outcome: if the realm's treasury cannot
 * cover the debit, {@link #withdraw}'s already-applied (but not yet
 * committed) player credit is rolled back along with everything else in the
 * transaction, and the future completes with {@code false} — the player
 * never actually receives anything unless the realm's debit also succeeds.
 *
 * <p><b>Transaction boundaries.</b> Same shape as {@code RealmServiceImpl}
 * and {@code EconomyServiceImpl}: {@link #inTransaction} explicitly
 * demarcates {@code setAutoCommit(false)}/{@code commit}/{@code rollback}
 * around every {@code LedgerDao} call (or pair of calls) here, since
 * {@code AsyncDatabaseExecutor.submit(...)} itself does no transaction
 * management.
 */
public final class TreasuryServiceImpl implements TreasuryService {

    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final RealmCache realmCache;
    private final RealmDao realmDao;
    private final RealmMemberDao realmMemberDao;
    private final RealmRankDao realmRankDao;
    private final LedgerDao ledgerDao;

    public TreasuryServiceImpl(
            AsyncDatabaseExecutor asyncDatabaseExecutor,
            RealmCache realmCache,
            RealmDao realmDao,
            RealmMemberDao realmMemberDao,
            RealmRankDao realmRankDao,
            LedgerDao ledgerDao
    ) {
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.realmCache = realmCache;
        this.realmDao = realmDao;
        this.realmMemberDao = realmMemberDao;
        this.realmRankDao = realmRankDao;
        this.ledgerDao = ledgerDao;
    }

    @Override
    public CompletableFuture<Money> balanceOf(long realmId) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        return asyncDatabaseExecutor.submit(connection -> {
            try {
                return Money.ofCents(realmDao.findBalance(connection, realmId));
            } catch (SQLException e) {
                throw new EconomyPersistenceException(
                        "Failed to read treasury balance for realm " + realmId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deposit(long realmId, UUID actor, Money amount) {
        requirePositive(amount);
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        long deltaCents = amount.cents();
        String realmIdString = String.valueOf(realmId);

        return asyncDatabaseExecutor.<Boolean>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    if (!ledgerDao.recordAndApplyToPlayer(
                            conn, actor, -deltaCents, TransactionCategory.TRANSFER,
                            "REALM_DEPOSIT", LedgerEntity.REALM, realmIdString)) {
                        throw InsufficientFundsSignal.INSTANCE;
                    }
                    if (!ledgerDao.recordAndApplyToRealm(
                            conn, realmId, deltaCents, TransactionCategory.TRANSFER,
                            "REALM_DEPOSIT", LedgerEntity.PLAYER, actor.toString())) {
                        // The player was just successfully debited, so the
                        // realm's treasury credit (a positive delta) failing
                        // means the realm row itself vanished mid-transaction
                        // (e.g. a concurrent disband) — not an ordinary
                        // "insufficient funds" case. Roll back and surface it
                        // as the genuine anomaly it is.
                        throw new IllegalStateException(
                                "Failed to credit realm " + realmId + " after debiting player " + actor);
                    }
                    return true;
                });
            } catch (InsufficientFundsSignal signal) {
                return false;
            } catch (SQLException e) {
                throw new EconomyPersistenceException(
                        "Failed to transfer " + amount + " from player " + actor + " to realm " + realmId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> withdraw(long realmId, UUID actor, Money amount) {
        requirePositive(amount);
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        long deltaCents = amount.cents();
        String realmIdString = String.valueOf(realmId);

        return asyncDatabaseExecutor.<Boolean>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    requireWithdrawPermission(conn, realmId, actor);

                    // Player wallet first, realm treasury second — fixed
                    // lock ordering, see class Javadoc.
                    if (!ledgerDao.recordAndApplyToPlayer(
                            conn, actor, deltaCents, TransactionCategory.TRANSFER,
                            "REALM_WITHDRAW", LedgerEntity.REALM, realmIdString)) {
                        // Crediting the player (a positive delta) cannot
                        // fail on insufficient funds — see EconomyServiceImpl
                        // for the same reasoning applied to deposit().
                        throw new IllegalStateException(
                                "Failed to credit player " + actor + " from realm " + realmId);
                    }
                    if (!ledgerDao.recordAndApplyToRealm(
                            conn, realmId, -deltaCents, TransactionCategory.TRANSFER,
                            "REALM_WITHDRAW", LedgerEntity.PLAYER, actor.toString())) {
                        throw InsufficientFundsSignal.INSTANCE;
                    }
                    return true;
                });
            } catch (InsufficientFundsSignal signal) {
                return false;
            } catch (SQLException e) {
                throw new EconomyPersistenceException(
                        "Failed to transfer " + amount + " from realm " + realmId + " to player " + actor, e);
            }
        });
    }

    /**
     * Resolves {@code actor}'s current rank within {@code realmId} and
     * requires it to carry {@link RealmPermission#WITHDRAW}. See this
     * class's Javadoc for why this cannot be answered from a cache and must
     * read {@code realm_members}/{@code realm_ranks} here instead.
     */
    private void requireWithdrawPermission(Connection connection, long realmId, UUID actor) throws SQLException {
        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, actor)
                .filter(member -> member.realmId() == realmId)
                .orElseThrow(() -> new PlayerNotInRealmException(actor));

        RealmRank rank = realmRankDao.findById(connection, membership.rankId())
                .orElseThrow(() -> new IllegalStateException(
                        "Rank " + membership.rankId() + " referenced by a member but missing"));

        if (!RealmPermission.has(rank.permissions(), RealmPermission.WITHDRAW)) {
            throw new MissingPermissionException(actor, RealmPermission.WITHDRAW);
        }
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
    }

    /**
     * Runs {@code work} inside an explicit transaction on {@code connection}.
     * Identical in shape to {@code RealmServiceImpl}'s and
     * {@code EconomyServiceImpl}'s private helpers of the same name; not
     * shared between the three classes yet (see {@code RealmServiceImpl}'s
     * Javadoc for why a shared helper is a reasonable future follow-up but
     * out of scope here).
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

    /**
     * Internal control-flow signal only: thrown from inside {@link
     * #inTransaction} to force a rollback when a transfer's debit side
     * reports insufficient funds, then caught right outside {@code submit}'s
     * lambda and translated into a plain {@code false} result. Never escapes
     * this class. Carries no message/cause/stack trace since it is not an
     * error — just a cheap, single, reused signal.
     */
    private static final class InsufficientFundsSignal extends RuntimeException {
        private static final InsufficientFundsSignal INSTANCE = new InsufficientFundsSignal();

        private InsufficientFundsSignal() {
            super(null, null, false, false);
        }
    }
}
