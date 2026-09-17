package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.RealmClaim;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.RealmClaimDao;
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.persistence.dao.RealmRankDao;
import com.flamerealms.service.exception.ChunkAlreadyClaimedException;
import com.flamerealms.service.exception.ClaimNotContiguousException;
import com.flamerealms.service.exception.InsufficientTreasuryFundsException;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.exception.PlayerNotInRealmException;
import com.flamerealms.service.exception.RealmNotFoundException;
import com.flamerealms.service.exception.RealmPersistenceException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ClaimService} implementation.
 *
 * <p><b>Realm existence.</b> Both methods fast-fail against {@link
 * RealmCache} — the same synchronous pre-check {@code RealmServiceImpl} and
 * {@code TreasuryServiceImpl} use elsewhere — before ever dispatching to
 * {@link AsyncDatabaseExecutor}, so an unknown {@code realmId} never reaches
 * the database at all.
 *
 * <p><b>The {@code CLAIM}/{@code UNCLAIM} permission checks have no cache to
 * answer them synchronously,</b> for exactly the reason {@code
 * TreasuryServiceImpl}'s class Javadoc gives for its own {@code WITHDRAW}
 * check: {@link RealmCache} caches only {@code id -> Realm}, {@code name ->
 * id}, {@code player -> realmId} and claim tracking — no rank/permission
 * data. So {@link #requirePermission} reads {@code realm_members} and {@code
 * realm_ranks} through {@link RealmMemberDao}/{@link RealmRankDao} inside the
 * same database dispatch as the mutation itself, as the very first thing
 * inside the transaction — exactly {@code TreasuryServiceImpl#
 * requireWithdrawPermission}'s pattern, reused here for {@code CLAIM}/{@code
 * UNCLAIM} instead of {@code WITHDRAW}. A failed check therefore never
 * mutates {@code realms}, {@code realm_claims} or {@code transactions} —
 * only a read happens. An actor with a membership row in some other realm
 * (i.e. {@code realm_members.realm_id != realmId}) is treated identically to
 * an actor with no membership at all: both surface as {@link
 * PlayerNotInRealmException}, since {@link RealmMemberDao#findByPlayerUuid}
 * is filtered down to {@code realmId} before the permission bit is even
 * inspected.
 *
 * <p><b>Claim purchase: one DB transaction for withdraw + claim-insert +
 * ledger-insert.</b> {@link #purchaseClaim} runs the permission check, the
 * contiguity check, the {@link LedgerDao#recordAndApplyToRealm} debit and the
 * {@link RealmClaimDao#insert} all on the same {@link Connection} inside one
 * {@link #inTransaction} call, so a losing race on either the treasury debit
 * or the {@code uq_chunk} unique constraint rolls back everything — a realm
 * can never end up debited for a claim it doesn't actually own, and never
 * ends up owning a claim it didn't pay for.
 *
 * <p><b>Contiguity is checked against existing claims only, never a
 * Nexus.</b> A realm's very first claim always succeeds regardless of where
 * it is — there is no Nexus location to anchor against yet in M2 (it arrives
 * in M4), so "the realm owns zero claims" is the only case that skips the
 * adjacency check. Every claim after the first must be {@link
 * ChunkCoordinate#isAdjacentTo} at least one chunk the realm already owns, in
 * the same world.
 *
 * <p><b>Insufficient treasury funds is a thrown exception here, not a
 * boolean.</b> {@code TreasuryServiceImpl} uses an {@code
 * InsufficientFundsSignal}-style internal control-flow exception, thrown
 * inside {@link #inTransaction} purely to force a rollback, then caught right
 * outside that call and translated into a plain {@code false}. {@link
 * #purchaseClaim} follows the identical shape — its own {@link
 * InsufficientFundsSignal} forces the same rollback — but since it returns a
 * {@code CompletableFuture<RealmClaim>} rather than {@code
 * CompletableFuture<Boolean>}, there is no meaningful "successful but empty"
 * value to hand back; the signal is translated into an {@link
 * InsufficientTreasuryFundsException} instead, right outside {@link
 * #inTransaction}, carrying the price that couldn't be covered.
 *
 * <p><b>Unclaim never touches the ledger and never preserves connectivity.</b>
 * Per the project's 0%-refund decision on releasing a claim, {@link
 * #unclaimChunk} does not call {@link LedgerDao} at all — the price paid at
 * claim time is simply gone, with no {@code transactions} row for the
 * release. It also does not check whether removing this chunk would split
 * the realm's remaining territory into disconnected islands; that guard only
 * exists on the "add a claim" path (contiguity), never on "remove one" — a
 * fragmented territory is a valid, if perhaps undesirable, realm shape.
 *
 * <p><b>Post-commit cache writes.</b> Both methods apply their {@link
 * RealmCache} mutation ({@code addClaim}/{@code removeClaim}) in a {@code
 * .thenApply(...)} chained onto the {@code submit(...)} future, exactly the
 * pattern {@code RealmServiceImpl#createRealm} uses for its own {@code
 * realmCache.put(...)}/{@code realmCache.putMember(...)} calls, rather than
 * writing the cache from inside the transaction's work function itself. That
 * keeps every cache mutation strictly after the corresponding commit (never
 * speculative, per {@link RealmCache}'s own class Javadoc) while keeping the
 * write colocated with the database call it corresponds to, without needing
 * a second, separate continuation for the caller to remember to add.
 */
public final class ClaimServiceImpl implements ClaimService {

    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final RealmCache realmCache;
    private final RealmClaimDao realmClaimDao;
    private final RealmMemberDao realmMemberDao;
    private final RealmRankDao realmRankDao;
    private final LedgerDao ledgerDao;
    private final PricingConfig pricingConfig;

    public ClaimServiceImpl(
            AsyncDatabaseExecutor asyncDatabaseExecutor,
            RealmCache realmCache,
            RealmClaimDao realmClaimDao,
            RealmMemberDao realmMemberDao,
            RealmRankDao realmRankDao,
            LedgerDao ledgerDao,
            PricingConfig pricingConfig
    ) {
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.realmCache = realmCache;
        this.realmClaimDao = realmClaimDao;
        this.realmMemberDao = realmMemberDao;
        this.realmRankDao = realmRankDao;
        this.ledgerDao = ledgerDao;
        this.pricingConfig = pricingConfig;
    }

    @Override
    public CompletableFuture<RealmClaim> purchaseClaim(long realmId, UUID actor, String world, int chunkX, int chunkZ) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        ChunkCoordinate target = new ChunkCoordinate(world, chunkX, chunkZ);

        return asyncDatabaseExecutor.<RealmClaim>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    requirePermission(conn, realmId, actor, RealmPermission.CLAIM);

                    List<RealmClaim> existingClaims = realmClaimDao.findByRealm(conn, realmId);
                    requireContiguous(existingClaims, target);

                    long priceCents = pricingConfig.purchasePriceCents(existingClaims.size() + 1);

                    if (!ledgerDao.recordAndApplyToRealm(
                            conn, realmId, -priceCents, TransactionCategory.SINK,
                            "CLAIM_PURCHASE", LedgerEntity.SERVER, null)) {
                        throw new InsufficientFundsSignal(priceCents);
                    }

                    Instant claimedAt = Instant.now();
                    long generatedId;
                    try {
                        generatedId = realmClaimDao.insert(
                                conn, new RealmClaim(0L, realmId, world, chunkX, chunkZ, claimedAt, priceCents));
                    } catch (SQLIntegrityConstraintViolationException e) {
                        // uq_chunk lost a race against a concurrent purchase of
                        // this exact chunk. Rolling back here (via the
                        // exception below propagating out of inTransaction's
                        // work) also undoes the treasury debit just applied
                        // above — exactly why both calls share one transaction.
                        throw new ChunkAlreadyClaimedException(target, e);
                    }

                    return new RealmClaim(generatedId, realmId, world, chunkX, chunkZ, claimedAt, priceCents);
                });
            } catch (InsufficientFundsSignal signal) {
                throw new InsufficientTreasuryFundsException(realmId, signal.priceCents);
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to purchase claim " + target + " for realm " + realmId, e);
            }
        }).thenApply(claim -> {
            realmCache.addClaim(realmId, claim.coordinate());
            return claim;
        });
    }

    @Override
    public CompletableFuture<Boolean> unclaimChunk(long realmId, UUID actor, String world, int chunkX, int chunkZ) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        ChunkCoordinate target = new ChunkCoordinate(world, chunkX, chunkZ);

        return asyncDatabaseExecutor.<Boolean>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    requirePermission(conn, realmId, actor, RealmPermission.UNCLAIM);

                    // No ledger entry, no refund (0% unclaim refund — see
                    // class Javadoc), and no attempt to keep the realm's
                    // remaining claims contiguous: fragmenting the territory
                    // by removing a chunk is explicitly allowed. Contiguity
                    // is only ever enforced when a claim is added.
                    return realmClaimDao.delete(conn, realmId, world, chunkX, chunkZ);
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to unclaim chunk " + target + " for realm " + realmId, e);
            }
        }).thenApply(deleted -> {
            if (deleted) {
                realmCache.removeClaim(target);
            }
            return deleted;
        });
    }

    /**
     * Resolves {@code actor}'s current rank within {@code realmId} and
     * requires it to carry {@code permission}. Shared by both {@link
     * #purchaseClaim} ({@code CLAIM}) and {@link #unclaimChunk} ({@code
     * UNCLAIM}) — see this class's Javadoc for why this cannot be answered
     * from a cache and must read {@code realm_members}/{@code realm_ranks}
     * here instead.
     */
    private void requirePermission(Connection connection, long realmId, UUID actor, RealmPermission permission)
            throws SQLException {
        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, actor)
                .filter(member -> member.realmId() == realmId)
                .orElseThrow(() -> new PlayerNotInRealmException(actor));

        RealmRank rank = realmRankDao.findById(connection, membership.rankId())
                .orElseThrow(() -> new IllegalStateException(
                        "Rank " + membership.rankId() + " referenced by a member but missing"));

        if (!RealmPermission.has(rank.permissions(), permission)) {
            throw new MissingPermissionException(actor, permission);
        }
    }

    /**
     * Requires {@code target} to be orthogonally adjacent to at least one of
     * {@code existingClaims}, unless {@code existingClaims} is empty — a
     * realm's very first claim always seeds its territory. See this class's
     * Javadoc for why this is checked against existing claims only, never a
     * Nexus location.
     */
    private static void requireContiguous(List<RealmClaim> existingClaims, ChunkCoordinate target) {
        if (existingClaims.isEmpty()) {
            return;
        }
        boolean adjacentToExistingClaim = existingClaims.stream()
                .map(RealmClaim::coordinate)
                .anyMatch(target::isAdjacentTo);
        if (!adjacentToExistingClaim) {
            throw new ClaimNotContiguousException(target);
        }
    }

    /**
     * Runs {@code work} inside an explicit transaction on {@code connection}.
     * Identical in shape to {@code RealmServiceImpl}'s and {@code
     * TreasuryServiceImpl}'s private helpers of the same name; not shared
     * between the three classes yet, same as those two note about each
     * other.
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
     * #inTransaction} to force a rollback when {@link
     * LedgerDao#recordAndApplyToRealm} reports the treasury can't cover the
     * claim's price, then caught right outside that call and translated into
     * an {@link InsufficientTreasuryFundsException}. Never escapes this
     * class. Unlike {@code TreasuryServiceImpl}'s singleton {@code
     * InsufficientFundsSignal}, this one carries the price that couldn't be
     * covered (needed for the thrown exception's message), so it is a
     * regular, per-call instance rather than a reused constant — still with
     * no message/cause/stack trace of its own, since it is not an error, just
     * a cheap signal.
     */
    private static final class InsufficientFundsSignal extends RuntimeException {
        private final long priceCents;

        private InsufficientFundsSignal(long priceCents) {
            super(null, null, false, false);
            this.priceCents = priceCents;
        }
    }
}
