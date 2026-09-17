package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.BlockCoordinate;
import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmClaim;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.RealmClaimDao;
import com.flamerealms.persistence.dao.RealmDao;
import com.flamerealms.persistence.dao.RealmInviteDao;
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.persistence.dao.RealmRankDao;
import com.flamerealms.service.exception.CannotKickLeaderException;
import com.flamerealms.service.exception.InsufficientFundsForRealmCreationException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link RealmService} implementation.
 *
 * <p><b>Invites are persisted, not in-memory.</b> Earlier (M1) revisions of
 * this class kept pending invites in a bare {@code ConcurrentHashMap}, since
 * no {@code realm_invites} table existed yet. {@code
 * V4__nexus_and_management.sql} added one, so {@link #invite} and {@link
 * #join} now go through {@link RealmInviteDao} like everything else here —
 * that is also why {@link #invite} changed from a synchronous, no-I/O method
 * to one returning a {@link CompletableFuture} like every other mutator on
 * this class: its storage moved from an in-memory map to the database, and a
 * database write cannot happen synchronously on the caller's thread. One
 * consequence carries over unchanged from the old in-memory scheme: {@link
 * #invite} still takes no permission check on {@code inviter} — {@link
 * RealmCache} deliberately caches only {@code id -> Realm}, {@code name ->
 * id}, {@code player -> realmId} and claim tracking (no rank/permission
 * data), and there is no synchronous "does this player hold {@code INVITE}"
 * read available. That enforcement belongs to the command layer (or a future
 * rank-permission cache), same as before.
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
 * <p><b>Realm creation is one atomic act: fee, nexus, and founding claim
 * together.</b> {@link #createRealm} now requires a Nexus location and, in a
 * single transaction, (a) charges the leader's personal wallet the creation
 * fee via {@link LedgerDao#recordAndApplyToPlayer} — insufficient personal
 * funds rolls back the whole transaction and surfaces {@link
 * InsufficientFundsForRealmCreationException}, using the same {@code
 * InsufficientFundsSignal}-style internal control-flow exception {@code
 * TreasuryServiceImpl} uses for its own transfers — (b) inserts the realm
 * row with its nexus columns already set, (c) seeds the three default ranks
 * and the leader's membership exactly as before, and (d) claims the Nexus's
 * own chunk for the realm, for free ({@code price_paid_cents = 0}), inserted
 * directly via {@link RealmClaimDao} rather than routed through {@code
 * ClaimServiceImpl} — this founding claim needs no contiguity check and no
 * permission check, since it is the realm's founding act, not an ordinary
 * purchase. The Nexus's block coordinates are converted to a chunk coordinate
 * with {@link Math#floorDiv}, not {@code >> 4} or naive division, since only
 * {@code floorDiv} handles negative coordinates correctly. This service has
 * no Bukkit dependency and never will: the caller (the command layer) is
 * responsible for having already verified a Beacon block physically exists
 * at the given location before calling {@link #createRealm} at all.
 *
 * <p><b>Kick vs. leave vs. transfer.</b> {@link #kick} mirrors {@link #leave}
 * (delete a {@code realm_members} row, then {@code
 * realmCache.removeMember(...)} post-commit) but is actor-driven rather than
 * self-driven, so it needs a {@code KICK} permission check on {@code actor}
 * first — read {@code realm_members}/{@code realm_ranks} inside the
 * transaction, exactly the {@code requireXxxPermission} shape {@code
 * TreasuryServiceImpl#requireWithdrawPermission}/{@code ClaimServiceImpl#
 * requirePermission} already use, for the same "no cache to answer it"
 * reason documented there. A realm's leader can never be kicked ({@link
 * CannotKickLeaderException}) — only {@link #transferLeadership} or {@link
 * #disbandRealm} can move a leader out of that role. {@link
 * #transferLeadership} itself touches only {@code realms.leader_uuid} and
 * the outgoing/incoming leaders' {@code rank_id}s (an actual swap, not just
 * "give the new leader the Leader rank") — realms, claims and the treasury
 * are otherwise completely untouched, per this project's own TODO
 * description of the feature.
 *
 * <p><b>Disband is realm/membership/claims/invites-only.</b> {@link
 * #disbandRealm} marks the realm disbanded and then deletes its row
 * outright, which cascades to {@code realm_ranks}, {@code realm_members},
 * {@code realm_claims} (since {@code V3__claims.sql}) and {@code
 * realm_invites} (since {@code V4__nexus_and_management.sql}) via their
 * {@code ON DELETE CASCADE} foreign keys to {@code realms.id} — the only
 * mechanism available without adding bulk "delete all X for a realm" methods
 * to a DAO contract this stage was told to treat as fixed;
 * {@link RealmCache#removeAllClaimsOf} mirrors the claims half of that
 * cascade in the cache the same way {@link RealmCache#removeRealm} already
 * does for members. There is no cache-side invite cleanup needed any more:
 * unlike claims/members, {@link RealmCache} never tracked invites in the
 * first place. Treasury destruction and the war-in-progress guard are not
 * implemented here; they arrive with the milestones that introduce a
 * treasury and wars.
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
    private final RealmClaimDao realmClaimDao;
    private final RealmInviteDao realmInviteDao;
    private final LedgerDao ledgerDao;
    private final PricingConfig pricingConfig;

    public RealmServiceImpl(
            AsyncDatabaseExecutor asyncDatabaseExecutor,
            RealmCache realmCache,
            RealmDao realmDao,
            RealmRankDao realmRankDao,
            RealmMemberDao realmMemberDao,
            RealmClaimDao realmClaimDao,
            RealmInviteDao realmInviteDao,
            LedgerDao ledgerDao,
            PricingConfig pricingConfig
    ) {
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.realmCache = realmCache;
        this.realmDao = realmDao;
        this.realmRankDao = realmRankDao;
        this.realmMemberDao = realmMemberDao;
        this.realmClaimDao = realmClaimDao;
        this.realmInviteDao = realmInviteDao;
        this.ledgerDao = ledgerDao;
        this.pricingConfig = pricingConfig;
    }

    @Override
    public CompletableFuture<Realm> createRealm(
            UUID leader, String name, String nexusWorld, int nexusX, int nexusY, int nexusZ) {
        // Fast-fail pre-check against the cache for quick feedback. The DB's
        // unique constraints are still the authority — see insertNewRealm's
        // per-insert catches below for what happens when this race is lost.
        if (realmCache.isPlayerInRealm(leader)) {
            return CompletableFuture.failedFuture(new PlayerAlreadyInRealmException(leader));
        }
        if (realmCache.isNameTaken(name)) {
            return CompletableFuture.failedFuture(new RealmNameTakenException(name));
        }

        // Block -> chunk coordinates via floorDiv (not >> 4 / naive division)
        // so negative nexus coordinates land in the correct chunk.
        int chunkX = Math.floorDiv(nexusX, 16);
        int chunkZ = Math.floorDiv(nexusZ, 16);

        return asyncDatabaseExecutor.<Realm>submit(connection -> {
            try {
                return inTransaction(connection, conn ->
                        insertNewRealm(conn, leader, name, nexusWorld, nexusX, nexusY, nexusZ, chunkX, chunkZ));
            } catch (InsufficientFundsSignal signal) {
                throw new InsufficientFundsForRealmCreationException(leader, pricingConfig.realmCreationFeeCents());
            } catch (SQLException e) {
                throw new RealmPersistenceException("Failed to create realm '" + name + "'", e);
            }
        }).thenApply(realm -> {
            realmCache.put(realm);
            realmCache.putMember(leader, realm.id());
            realmCache.addClaim(realm.id(), new ChunkCoordinate(nexusWorld, chunkX, chunkZ));
            realmCache.addNexus(new BlockCoordinate(nexusWorld, nexusX, nexusY, nexusZ));
            return realm;
        });
    }

    private Realm insertNewRealm(
            Connection connection, UUID leader, String name,
            String nexusWorld, int nexusX, int nexusY, int nexusZ, int chunkX, int chunkZ) throws SQLException {
        Instant now = Instant.now();

        // (a) Charge the leader's personal wallet the creation fee, before
        // anything else is written. A losing race here rolls back the whole
        // transaction — no realm, no ranks, no membership, no claim.
        if (!ledgerDao.recordAndApplyToPlayer(
                connection, leader, -pricingConfig.realmCreationFeeCents(), TransactionCategory.SINK,
                "REALM_CREATION_FEE", LedgerEntity.SERVER, null)) {
            throw InsufficientFundsSignal.INSTANCE;
        }

        // (b) Insert the realm row, nexus columns already set.
        Realm inserted;
        try {
            inserted = realmDao.insert(connection, new Realm(0L, name, name, leader, 1, now, null,
                    nexusWorld, nexusX, nexusY, nexusZ));
        } catch (SQLIntegrityConstraintViolationException e) {
            // realms.name lost the uniqueness race the cache pre-check missed.
            throw new RealmNameTakenException(name, e);
        }

        // (c) Seed the three default ranks and the leader's own membership.
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

        // (d) Auto-claim the Nexus's own chunk as the realm's first claim,
        // for free. No contiguity check, no permission check, and not routed
        // through ClaimServiceImpl — this is the realm's founding act, not
        // an ordinary purchase.
        realmClaimDao.insert(connection, new RealmClaim(0L, inserted.id(), nexusWorld, chunkX, chunkZ, now, 0L));

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
                    // Deletes realms row; ON DELETE CASCADE on realm_ranks.realm_id,
                    // realm_members.realm_id, realm_claims.realm_id and
                    // realm_invites.realm_id removes all of it with it.
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
            // The disbanding realm's Nexus becomes an ordinary, breakable
            // block again. Captured off the `realm` object already in scope
            // (read before the disband call happened) rather than re-reading
            // it post-commit — same "capture before, act after" shape
            // RealmActions already uses for its own claim-resync-before-leave
            // logic. Guarded for null: a realm created before
            // V4__nexus_and_management.sql has all four nexus columns NULL
            // (see Realm's own Javadoc) and was never tracked as a Nexus.
            if (realm.nexusWorld() != null) {
                realmCache.removeNexus(new BlockCoordinate(
                        realm.nexusWorld(), realm.nexusX(), realm.nexusY(), realm.nexusZ()));
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> invite(long realmId, UUID inviter, UUID target) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    // No permission check on `inviter` here — see class Javadoc.
                    realmInviteDao.insert(conn, realmId, target);
                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to invite player " + target + " to realm " + realmId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> join(UUID player, long realmId) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }
        if (realmCache.isPlayerInRealm(player)) {
            return CompletableFuture.failedFuture(new PlayerAlreadyInRealmException(player));
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    if (!realmInviteDao.exists(conn, realmId, player)) {
                        throw new NotInvitedException(player, realmId);
                    }

                    RealmRank defaultRank = realmRankDao.findDefaultRank(conn, realmId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Realm " + realmId + " has no default rank"));

                    try {
                        realmMemberDao.insert(
                                conn, new RealmMember(realmId, player, defaultRank.id(), Instant.now()));
                    } catch (SQLIntegrityConstraintViolationException e) {
                        throw new PlayerAlreadyInRealmException(player, e);
                    }

                    // Consume the invite in the same transaction as the
                    // membership insert, so both commit together.
                    realmInviteDao.delete(conn, realmId, player);

                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed for player " + player + " to join realm " + realmId, e);
            }
        }).thenApply(v -> {
            realmCache.putMember(player, realmId);
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
    public CompletableFuture<Void> kick(long realmId, UUID actor, UUID target) {
        Realm realm = realmCache.get(realmId).orElse(null);
        if (realm == null) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    requireKickPermission(conn, realmId, actor);

                    realmMemberDao.findByPlayerUuid(conn, target)
                            .filter(member -> member.realmId() == realmId)
                            .orElseThrow(() -> new PlayerNotInRealmException(target));

                    if (target.equals(realm.leaderUuid())) {
                        throw new CannotKickLeaderException(target);
                    }

                    realmMemberDao.delete(conn, realmId, target);
                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to kick player " + target + " from realm " + realmId, e);
            }
        }).thenApply(v -> {
            realmCache.removeMember(target);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> transferLeadership(long realmId, UUID currentLeader, UUID newLeader) {
        Realm realm = realmCache.get(realmId).orElse(null);
        if (realm == null) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }
        if (!realm.leaderUuid().equals(currentLeader)) {
            return CompletableFuture.failedFuture(new NotRealmLeaderException(currentLeader, realmId));
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    RealmMember newLeaderMembership = realmMemberDao.findByPlayerUuid(conn, newLeader)
                            .filter(member -> member.realmId() == realmId)
                            .orElseThrow(() -> new PlayerNotInRealmException(newLeader));

                    // Transferring to yourself is a no-op, not an error.
                    if (newLeader.equals(currentLeader)) {
                        return null;
                    }

                    // Read newLeader's pre-swap rank before either update, so
                    // currentLeader ends up with what newLeader actually held.
                    long newLeaderPreviousRankId = newLeaderMembership.rankId();

                    RealmRank leaderRank = realmRankDao.findByRealmAndName(conn, realmId, "Leader")
                            .orElseThrow(() -> new IllegalStateException(
                                    "Realm " + realmId + " has no Leader rank"));

                    realmMemberDao.updateRank(conn, realmId, newLeader, leaderRank.id());
                    realmMemberDao.updateRank(conn, realmId, currentLeader, newLeaderPreviousRankId);
                    realmDao.updateLeader(conn, realmId, newLeader);
                    return null;
                });
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to transfer leadership of realm " + realmId + " to " + newLeader, e);
            }
        }).thenApply(v -> {
            // Records have no built-in "with" — construct a new Realm
            // positionally, same field values except leaderUuid.
            realmCache.put(new Realm(realm.id(), realm.name(), realm.displayName(), newLeader,
                    realm.level(), realm.createdAt(), realm.disbandedAt(),
                    realm.nexusWorld(), realm.nexusX(), realm.nexusY(), realm.nexusZ()));
            return null;
        });
    }

    @Override
    public Optional<Realm> getByPlayer(UUID player) {
        return realmCache.getByPlayer(player);
    }

    @Override
    public Optional<Realm> getByName(String name) {
        return realmCache.getByName(name);
    }

    @Override
    public CompletableFuture<List<UUID>> getMemberUuids(long realmId) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        return asyncDatabaseExecutor.submit(connection -> {
            try {
                return realmMemberDao.findAllPlayerUuids(connection, realmId);
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to load member UUIDs for realm " + realmId, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<RealmRank>> getRanks(long realmId) {
        if (realmCache.get(realmId).isEmpty()) {
            return CompletableFuture.failedFuture(new RealmNotFoundException(realmId));
        }

        return asyncDatabaseExecutor.submit(connection -> {
            try {
                return realmRankDao.findAllByRealm(connection, realmId);
            } catch (SQLException e) {
                throw new RealmPersistenceException("Failed to load ranks for realm " + realmId, e);
            }
        });
    }

    /**
     * Resolves {@code actor}'s current rank within {@code realmId} and
     * requires it to carry {@link RealmPermission#KICK}. Same {@code
     * requireXxxPermission}-in-transaction shape as {@code TreasuryServiceImpl#
     * requireWithdrawPermission} / {@code ClaimServiceImpl#requirePermission} —
     * see this class's Javadoc for why this cannot be answered from a cache
     * and must read {@code realm_members}/{@code realm_ranks} here instead.
     */
    private void requireKickPermission(Connection connection, long realmId, UUID actor) throws SQLException {
        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, actor)
                .filter(member -> member.realmId() == realmId)
                .orElseThrow(() -> new PlayerNotInRealmException(actor));

        RealmRank rank = realmRankDao.findById(connection, membership.rankId())
                .orElseThrow(() -> new IllegalStateException(
                        "Rank " + membership.rankId() + " referenced by a member but missing"));

        if (!RealmPermission.has(rank.permissions(), RealmPermission.KICK)) {
            throw new MissingPermissionException(actor, RealmPermission.KICK);
        }
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

    /**
     * Internal control-flow signal only: thrown from inside {@link
     * #inTransaction} to force a rollback when {@link #createRealm}'s
     * creation-fee debit reports the leader's wallet can't cover it, then
     * caught right outside that call and translated into an {@link
     * InsufficientFundsForRealmCreationException}. Never escapes this class.
     * The fee itself is a fixed, statically-known {@link PricingConfig}
     * value (unlike {@code ClaimServiceImpl}'s per-purchase price), so — same
     * as {@code TreasuryServiceImpl}'s own signal — this is a reused
     * singleton with no message/cause/stack trace of its own, since it is not
     * an error, just a cheap signal.
     */
    private static final class InsufficientFundsSignal extends RuntimeException {
        private static final InsufficientFundsSignal INSTANCE = new InsufficientFundsSignal();

        private InsufficientFundsSignal() {
            super(null, null, false, false);
        }
    }
}
