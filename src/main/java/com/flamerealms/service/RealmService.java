package com.flamerealms.service;

import com.flamerealms.domain.Realm;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The realm/membership use cases for M1's scope: create, disband, invite,
 * join, leave, and re-rank a member. Balance/treasury/claims/diplomacy/
 * nexus are later milestones and have no methods here.
 *
 * <p>Every mutating method dispatches through
 * {@code AsyncDatabaseExecutor.submit(...)} under the hood and returns a
 * {@link CompletableFuture} — never call {@code .join()}/{@code .get()} on
 * one of these from the Paper main thread; apply the result via
 * {@code Bukkit.getScheduler().runTask(...)} in a continuation instead.
 * {@link #invite}, {@link #getByPlayer} and {@link #getByName} are the
 * exceptions: they touch no I/O (invites are in-memory only in M1; the two
 * getters are pure {@code RealmCache} reads), so they are synchronous and
 * safe to call from the main thread directly.
 *
 * <p>A failed future completes exceptionally with a
 * {@code com.flamerealms.service.exception.RealmServiceException} subtype
 * describing exactly what rule was violated (name taken, player already in
 * a realm, not the leader, missing permission, not invited, ...) — never
 * with a raw {@link java.sql.SQLException}.
 */
public interface RealmService {

    /**
     * Creates a new realm led by {@code leader}, seeded with the three
     * default ranks (Leader, Officer, Member) and the leader's own
     * membership row, all in one transaction.
     */
    CompletableFuture<Realm> createRealm(UUID leader, String name);

    /**
     * Disbands the realm identified by {@code realmId}. Only that realm's
     * leader may do this. Realm/membership-only for M1 — treasury
     * destruction, claim release and the war guard arrive with their own
     * milestones.
     */
    CompletableFuture<Void> disbandRealm(long realmId, UUID requestedBy);

    /**
     * Records that {@code target} may join {@code realmId}. M1 keeps
     * invites in-memory only (no {@code realm_invites} table exists yet),
     * so this never touches the database and returns immediately — see
     * {@code RealmServiceImpl}'s class Javadoc for the full rationale.
     */
    void invite(long realmId, UUID inviter, UUID target);

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

    /** Synchronous {@code RealmCache} read: the realm {@code player} currently belongs to, if any. */
    Optional<Realm> getByPlayer(UUID player);

    /** Synchronous {@code RealmCache} read: the realm named {@code name} (case-insensitive), if any. */
    Optional<Realm> getByName(String name);
}
