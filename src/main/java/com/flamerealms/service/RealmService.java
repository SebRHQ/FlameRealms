package com.flamerealms.service;

import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmRank;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The realm/membership use cases: create, disband, invite, join, leave,
 * re-rank a member, kick a member and transfer leadership. Claims/upkeep/
 * diplomacy live on their own service interfaces.
 *
 * <p>Every mutating method — {@link #invite} included, now that invites are
 * persisted (see below) — dispatches through
 * {@code AsyncDatabaseExecutor.submit(...)} under the hood and returns a
 * {@link CompletableFuture}: never call {@code .join()}/{@code .get()} on one
 * of these from the Paper main thread; apply the result via
 * {@code Bukkit.getScheduler().runTask(...)} in a continuation instead.
 * {@link #getByPlayer} and {@link #getByName} are the only exceptions: they
 * touch no I/O (pure {@code RealmCache} reads), so they are synchronous and
 * safe to call from the main thread directly.
 *
 * <p>A failed future completes exceptionally with a
 * {@code com.flamerealms.service.exception.RealmServiceException} subtype
 * describing exactly what rule was violated (name taken, player already in
 * a realm, not the leader, missing permission, not invited, insufficient
 * funds, ...) — never with a raw {@link java.sql.SQLException}.
 */
public interface RealmService {

    /**
     * Creates a new realm led by {@code leader}, anchored to a Nexus block at
     * {@code (nexusWorld, nexusX, nexusY, nexusZ)}, seeded with the three
     * default ranks (Leader, Officer, Member), the leader's own membership
     * row, and a free founding claim on the Nexus's own chunk — all in one
     * transaction, after charging {@code leader}'s personal wallet the realm
     * creation fee. The caller is responsible for having already verified a
     * Beacon block physically exists at the given coordinates; this service
     * has no Bukkit dependency and trusts the coordinates it is given.
     *
     * @throws com.flamerealms.service.exception.InsufficientFundsForRealmCreationException
     *         if {@code leader}'s personal wallet cannot cover the creation fee
     */
    CompletableFuture<Realm> createRealm(
            UUID leader, String name, String nexusWorld, int nexusX, int nexusY, int nexusZ);

    /**
     * Disbands the realm identified by {@code realmId}. Only that realm's
     * leader may do this. Realm/membership/claims-only — treasury
     * destruction and the war guard arrive with their own milestones.
     */
    CompletableFuture<Void> disbandRealm(long realmId, UUID requestedBy);

    /**
     * Records that {@code target} may join {@code realmId}, persisted in the
     * {@code realm_invites} table. No permission check on {@code inviter}
     * here — see {@code RealmServiceImpl}'s class Javadoc for why that
     * enforcement belongs to the command layer instead.
     */
    CompletableFuture<Void> invite(long realmId, UUID inviter, UUID target);

    /**
     * Consumes a pending invite recorded by {@link #invite} and adds
     * {@code player} to {@code realmId} at that realm's default rank.
     */
    CompletableFuture<Void> join(UUID player, long realmId);

    /**
     * Removes {@code player} from their current realm. Fails if they are
     * that realm's leader — a leader must transfer leadership or disband
     * instead of leaving.
     */
    CompletableFuture<Void> leave(UUID player);

    /**
     * Changes {@code target}'s rank within {@code realmId}. {@code actor}
     * must currently hold a rank with {@code MANAGE_RANKS} in that same
     * realm.
     */
    CompletableFuture<Void> setRank(long realmId, UUID actor, UUID target, long rankId);

    /**
     * Removes {@code target} from {@code realmId} against their will.
     * {@code actor} must currently hold a rank with {@code KICK} in that same
     * realm. {@code target} must be a current member of {@code realmId} and
     * may never be that realm's leader — a leader must transfer leadership
     * or disband, never be kicked.
     */
    CompletableFuture<Void> kick(long realmId, UUID actor, UUID target);

    /**
     * Transfers {@code realmId}'s leadership from {@code currentLeader} to
     * {@code newLeader}, swapping the two members' ranks (the new leader
     * takes the "Leader" rank, the outgoing leader takes whatever rank the
     * new leader held before the swap). Realms/claims/treasury are otherwise
     * completely untouched. Transferring to the current leader is a no-op
     * that still succeeds.
     *
     * @throws com.flamerealms.service.exception.NotRealmLeaderException
     *         if {@code currentLeader} is not actually {@code realmId}'s leader
     */
    CompletableFuture<Void> transferLeadership(long realmId, UUID currentLeader, UUID newLeader);

    /** Synchronous {@code RealmCache} read: the realm {@code player} currently belongs to, if any. */
    Optional<Realm> getByPlayer(UUID player);

    /** Synchronous {@code RealmCache} read: the realm named {@code name} (case-insensitive), if any. */
    Optional<Realm> getByName(String name);

    /**
     * Every player UUID currently belonging to {@code realmId}. Dispatched
     * through {@code AsyncDatabaseExecutor.submit(...)} the same as every
     * other mutating/reading method above that touches {@code realm_members}
     * directly rather than through {@code RealmCache} (which tracks {@code
     * player -> realmId}, not the reverse). Fast-fails against {@code
     * RealmCache#get(long)} first, same pattern as every other method here.
     */
    CompletableFuture<List<UUID>> getMemberUuids(long realmId);

    /**
     * Every rank belonging to {@code realmId}, highest priority first — a
     * trivial pass-through to
     * {@code RealmRankDao.findAllByRealm(Connection, long)}, dispatched
     * through {@code AsyncDatabaseExecutor.submit(...)} and fast-failing
     * against {@code RealmCache#get(long)} first, the exact same shape as
     * {@link #getMemberUuids}. Added for the chest-GUI rank-picker (a
     * {@code setrank} click needs to list a realm's ranks without the GUI
     * layer reaching into {@code RealmRankDao} directly).
     */
    CompletableFuture<List<RealmRank>> getRanks(long realmId);
}
