package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.dao.RealmDao;
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.persistence.dao.RealmRankDao;
import com.flamerealms.service.exception.LeaderCannotLeaveException;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.exception.NotInvitedException;
import com.flamerealms.service.exception.NotRealmLeaderException;
import com.flamerealms.service.exception.PlayerAlreadyInRealmException;
import com.flamerealms.service.exception.PlayerNotInRealmException;
import com.flamerealms.service.exception.RealmNameTakenException;
import com.flamerealms.service.exception.RealmNotFoundException;
import com.flamerealms.service.exception.RealmPersistenceException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RealmService} implementation.
 *
 * <p><b>Invites are in-memory only for M1.</b> There is no {@code
 * realm_invites} table in {@code V1__realm_core.sql}, so pending invites
 * live purely in the {@code pendingInvites} map below (realm id -&gt; set
 * of invited player UUIDs). This is a deliberate M1 simplification, not a
 * missed requirement: invites do not need to survive a plugin restart yet,
 * and adding a persisted table later is additive — it would not change
 * {@link RealmService}'s method signatures at all, just swap what backs
 * {@link #invite} and the pending-invite check inside {@link #join}.
 * One consequence worth calling out: {@link #invite} takes no permission
 * check on {@code inviter} — {@link RealmCache} deliberately caches only
 * {@code id -> Realm}, {@code name -> id} and {@code player -> realmId}
 * (no rank/permission data), and {@code invite()} is synchronous per the
 * {@link RealmService} contract, so there is no non-blocking way to verify
 * {@code inviter} holds {@code INVITE} here. That enforcement belongs to
 * the command layer (or a future rank-permission cache) — this method only
 * verifies the realm itself still exists.
 *
 * <p><b>Transaction boundaries.</b> {@code AsyncDatabaseExecutor.submit(...)}
 * hands out a connection in whatever auto-commit state HikariCP configured
 * (the project default is auto-commit {@code true}) and does no transaction
 * management of its own — every DAO call auto-commits by itself unless
 * something demarcates a transaction. Since every mutating method here
 * needs several DAO calls to commit or fail together, {@link #inTransaction}
 * does that explicitly: {@code setAutoCommit(false)}, run the work, {@code
 * commit()} on success or {@code rollback()} on any exception, then restore
 * the connection's original auto-commit state before handing it back to the
 * pool. This is scoped to this class only; {@code AsyncDatabaseExecutor} and
 * {@code DatabaseManager} are untouched. A shared transaction helper sitting
 * lower in the persistence layer would be a reasonable follow-up (the
 * project's own TODO already flags a shared {@code LedgerDao} helper along
 * similar lines for M2), but that is out of scope here.
 *
 * <p><b>Disband is realm/membership/claims-only for M1.</b> {@link #disbandRealm}
 * marks the realm disbanded and then deletes its row outright, which
 * cascades to {@code realm_ranks}, {@code realm_members} and (since
 * {@code V3__claims.sql}) {@code realm_claims} via their {@code ON DELETE
 * CASCADE} foreign keys to {@code realms.id} — the only mechanism available
 * without adding bulk "delete all ranks/members/claims for a realm" methods
 * to a DAO contract this stage was told to treat as fixed;
 * {@link RealmCache#removeAllClaimsOf} mirrors that cascade in the cache the
 * same way {@link RealmCache#removeRealm} already does for members. Treasury
 * destruction and the war-in-progress guard are not implemented here; they
 * arrive with the milestones that introduce a treasury and wars.
 *
 * <p>Every cache mutation below happens strictly inside the success
 * continuation of a completed database future — never before, never
 * optimistically, exactly as {@link RealmCache}'s own contract requires.
 */
public final class RealmServiceImpl implements RealmService {

    private static final long OFFICER_PERMISSIONS =
            RealmPermission.INVITE.bit() | RealmPermission.KICK.bit() | RealmPermission.CLAIM.bit()
                    | RealmPermission.DEPOSIT.bit() | RealmPermission.DIPLOMACY.bit();

    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final RealmCache realmCache;
    private final RealmDao realmDao;
    private final RealmRankDao realmRankDao;
    private final RealmMemberDao realmMemberDao;

    // realm id -> invited player UUIDs. In-memory only — see class Javadoc.
    private final ConcurrentHashMap<Long, Set<UUID>> pendingInvites = new ConcurrentHashMap<>();

    public RealmServiceImpl(
            AsyncDatabaseExecutor asyncDatabaseExecutor,
            RealmCache realmCache,
            RealmDao realmDao,
            RealmRankDao realmRankDao,
            RealmMemberDao realmMemberDao
    ) {
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.realmCache = realmCache;
        this.realmDao = realmDao;
        this.realmRankDao = realmRankDao;
        this.realmMemberDao = realmMemberDao;
    }

    @Override
    public CompletableFuture<Realm> createRealm(UUID leader, String name) {
        // Fast-fail pre-check against the cache for quick feedback. The DB's
        // unique constraints are still the authority — see insertNewRealm's
        // per-insert catches below for what happens when this race is lost.
        if (realmCache.isPlayerInRealm(leader)) {
            return CompletableFuture.failedFuture(new PlayerAlreadyInRealmException(leader));
        }
        if (realmCache.isNameTaken(name)) {
            return CompletableFuture.failedFuture(new RealmNameTakenException(name));
        }

        return asyncDatabaseExecutor.submit(connection -> {
            try {
                return inTransaction(connection, conn -> insertNewRealm(conn, leader, name));
            } catch (SQLException e) {
                throw new RealmPersistenceException("Failed to create realm '" + name + "'", e);
            }
        }).thenApply(realm -> {
            realmCache.put(realm);
            realmCache.putMember(leader, realm.id());
            return realm;
        });
    }

    private Realm insertNewRealm(Connection connection, UUID leader, String name) throws SQLException {
        Instant now = Instant.now();

        Realm inserted;
        try {
            inserted = realmDao.insert(connection, new Realm(0L, name, name, leader, 1, now, null));
        } catch (SQLIntegrityConstraintViolationException e) {
            // realms.name lost the uniqueness race the cache pre-check missed.
            throw new RealmNameTakenException(name, e);
        }

        RealmRank leaderRank = realmRankDao.insert(
                connection, new RealmRank(0L, inserted.id(), "Leader", 100, RealmPermission.ALL, false));
        realmRankDao.insert(
                connection, new RealmRank(0L, inserted.id(), "Officer", 50, OFFICER_PERMISSIONS, false));
        realmRankDao.insert(
                connection, new RealmRank(0L, inserted.id(), "Member", 0, 0L, true));

        try {
            realmMemberDao.insert(connection, new RealmMember(inserted.id(), leader, leaderRank.id(), now));
        } catch (SQLIntegrityConstraintViolationException e) {
            // realm_members.player_uuid (uq_player_one_realm) lost its race instead.
            throw new PlayerAlreadyInRealmException(leader, e);
        }

        return inserted;
    }

    @Override
    public CompletableFuture<Void> disbandRealm(long realmId, UUID requestedBy) {
        Realm realm = realmCache.get(realmId).orElse(null);
        if (realm == null) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }
        if (!realm.leaderUuid().equals(requestedBy)) {
            return CompletableFuture.failedFuture(new NotRealmLeaderException(requestedBy, realmId));
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    realmDao.markDisbanded(conn, realmId, Instant.now());
                    // Deletes realms row; ON DELETE CASCADE on realm_ranks.realm_id
                    // and realm_members.realm_id removes its ranks/members with it.
                    realmDao.delete(conn, realmId);
                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException("Failed to disband realm " + realmId, e);
            }
        }).thenApply(v -> {
            realmCache.removeRealm(realmId);
            // realm_claims.realm_id cascades via ON DELETE CASCADE (see
            // V3__claims.sql), same as realm_ranks/realm_members above, so
            // the cache needs the matching cleanup for claims too.
            realmCache.removeAllClaimsOf(realmId);
            pendingInvites.remove(realmId);
            return null;
        });
    }

    @Override
    public void invite(long realmId, UUID inviter, UUID target) {
        if (realmCache.get(realmId).isEmpty()) {
            throw new RealmNotFoundException(realmId);
        }
        // No permission check on `inviter` here — see class Javadoc.
        pendingInvites.computeIfAbsent(realmId, id -> ConcurrentHashMap.newKeySet()).add(target);
    }

    @Override
    public CompletableFuture<Void> join(UUID player, long realmId) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }
        if (realmCache.isPlayerInRealm(player)) {
            return CompletableFuture.failedFuture(new PlayerAlreadyInRealmException(player));
        }

        Set<UUID> invited = pendingInvites.get(realmId);
        if (invited == null || !invited.contains(player)) {
            return CompletableFuture.failedFuture(new NotInvitedException(player, realmId));
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    RealmRank defaultRank = realmRankDao.findDefaultRank(conn, realmId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Realm " + realmId + " has no default rank"));

                    try {
                        realmMemberDao.insert(
                                conn, new RealmMember(realmId, player, defaultRank.id(), Instant.now()));
                    } catch (SQLIntegrityConstraintViolationException e) {
                        throw new PlayerAlreadyInRealmException(player, e);
                    }

                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed for player " + player + " to join realm " + realmId, e);
            }
        }).thenApply(v -> {
            realmCache.putMember(player, realmId);
            Set<UUID> stillInvited = pendingInvites.get(realmId);
            if (stillInvited != null) {
                stillInvited.remove(player);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> leave(UUID player) {
        Realm realm = realmCache.getByPlayer(player).orElse(null);
        if (realm == null) {
            return CompletableFuture.failedFuture(new PlayerNotInRealmException(player));
        }
        if (realm.leaderUuid().equals(player)) {
            return CompletableFuture.failedFuture(new LeaderCannotLeaveException(player, realm.id()));
        }

        long realmId = realm.id();

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    realmMemberDao.delete(conn, realmId, player);
                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException("Failed for player " + player + " to leave realm " + realmId, e);
            }
        }).thenApply(v -> {
            realmCache.removeMember(player);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> setRank(long realmId, UUID actor, UUID target, long rankId) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    RealmMember actorMembership = realmMemberDao.findByPlayerUuid(conn, actor)
                            .filter(member -> member.realmId() == realmId)
                            .orElseThrow(() -> new PlayerNotInRealmException(actor));

                    RealmRank actorRank = realmRankDao.findById(conn, actorMembership.rankId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Rank " + actorMembership.rankId() + " referenced by a member but missing"));

                    if (!RealmPermission.has(actorRank.permissions(), RealmPermission.MANAGE_RANKS)) {
                        throw new MissingPermissionException(actor, RealmPermission.MANAGE_RANKS);
                    }

                    realmMemberDao.findByPlayerUuid(conn, target)
                            .filter(member -> member.realmId() == realmId)
                            .orElseThrow(() -> new PlayerNotInRealmException(target));

                    realmMemberDao.updateRank(conn, realmId, target, rankId);
                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to set rank " + rankId + " for player " + target + " in realm " + realmId, e);
            }
        });
        // No RealmCache mutation follows: the cache tracks player -> realm
        // membership only, never a member's rank, so nothing it holds
        // changes when realm_members.rank_id does.
    }

    @Override
    public Optional<Realm> getByPlayer(UUID player) {
        return realmCache.getByPlayer(player);
    }

    @Override
    public Optional<Realm> getByName(String name) {
        return realmCache.getByName(name);
    }

    /**
     * Runs {@code work} inside an explicit transaction on {@code connection}:
     * {@code setAutoCommit(false)}, then either {@code commit()} once
     * {@code work} returns or {@code rollback()} if it throws (checked or
     * unchecked), always restoring the connection's original auto-commit
     * state before returning. See the class Javadoc's "Transaction
     * boundaries" note for why this lives here instead of lower in the
     * persistence layer.
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
