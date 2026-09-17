package com.flamerealms.service;

import com.flamerealms.domain.RealmClaim;
import com.flamerealms.domain.RealmPermission;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Territory-claim use cases for M2's scope: buy a chunk into a realm's
 * territory, or release one. Territory size upkeep, the active-population
 * multiplier and every other consumer of {@code realm_claims} belong to
 * later milestones/stages — this interface only covers the two mutations
 * that change which chunks a realm owns.
 *
 * <p>Both methods dispatch through
 * {@code AsyncDatabaseExecutor.submit(...)} under the hood and return a
 * {@link CompletableFuture} — never call {@code .join()}/{@code .get()} on
 * one of these from the Paper main thread; apply the result via
 * {@code Bukkit.getScheduler().runTask(...)} in a continuation instead.
 *
 * <p>A failed future completes exceptionally with a
 * {@code com.flamerealms.service.exception.RealmServiceException} subtype —
 * the realm does not exist, {@code actor} lacks the required permission,
 * {@code actor} does not belong to {@code realmId}, the requested chunk is
 * already claimed, the requested chunk is not contiguous with the realm's
 * existing territory, or the realm's treasury cannot cover the claim's price
 * — never with a raw {@link java.sql.SQLException}.
 */
public interface ClaimService {

    /**
     * Buys the chunk at {@code (world, chunkX, chunkZ)} into {@code
     * realmId}'s territory. {@code actor} must currently hold a rank in
     * {@code realmId} that carries {@link RealmPermission#CLAIM}.
     *
     * <p>The requested chunk must be orthogonally adjacent to at least one
     * chunk {@code realmId} already has claimed, in the same world — unless
     * {@code realmId} owns no claims at all yet, in which case any chunk is
     * allowed (it seeds the realm's territory). Otherwise the future fails
     * with a {@code ClaimNotContiguousException}.
     *
     * <p>The price is charged against {@code realmId}'s treasury as a single
     * {@code SINK} ledger entry, in the same database transaction as the
     * claim row's insert — either both happen or neither does. If the
     * treasury cannot cover the price, the future fails with an
     * {@code InsufficientTreasuryFundsException} (not a boolean {@code
     * false}: unlike {@link TreasuryService}, there is no meaningful "empty
     * success" value a {@code CompletableFuture<RealmClaim>} could return
     * instead).
     *
     * @return the persisted claim, including its generated id and the price
     *         actually paid
     */
    CompletableFuture<RealmClaim> purchaseClaim(long realmId, UUID actor, String world, int chunkX, int chunkZ);

    /**
     * Releases the chunk at {@code (world, chunkX, chunkZ)} from {@code
     * realmId}'s territory. {@code actor} must currently hold a rank in
     * {@code realmId} that carries {@link RealmPermission#UNCLAIM}.
     *
     * <p>Per the project's 0%-refund decision on unclaiming, this never
     * creates a ledger entry and never refunds any money — whatever was
     * paid for the claim at purchase time is simply gone. It also never
     * attempts to preserve territory connectivity: unclaiming a chunk that
     * splits the realm's remaining territory into disconnected islands is
     * explicitly allowed. Contiguity is enforced only when a claim is
     * added, never when one is removed.
     *
     * @return {@code true} if a claim was actually removed; {@code false} —
     *         not an exception — if {@code realmId} had no claim at that
     *         chunk to begin with
     */
    CompletableFuture<Boolean> unclaimChunk(long realmId, UUID actor, String world, int chunkX, int chunkZ);
}
