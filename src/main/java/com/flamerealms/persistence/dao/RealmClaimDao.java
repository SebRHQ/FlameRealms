package com.flamerealms.persistence.dao;

import com.flamerealms.domain.RealmClaim;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;

/**
 * Data access for the {@code realm_claims} table.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — see
 * {@link RealmDao} for the composition contract this DAO shares with it.
 *
 * <p><b>No locking or free-chunk validation happens here.</b> The caller
 * (the Services stage) is responsible for having already confirmed the
 * target chunk is unclaimed and for running {@link #insert} inside a
 * transaction; {@code realm_claims}'s {@code uq_chunk} unique constraint
 * ({@code UNIQUE KEY uq_chunk (world, chunk_x, chunk_z)}, see
 * {@code V3__claims.sql}) is only a last-resort guarantee against a genuine
 * race between two claims of the same chunk. {@link #insert} does not catch
 * that violation itself — it is a subtype of {@link SQLException}, so it
 * propagates undisguised, exactly like every other DAO in this project
 * (compare {@code RealmDao#insert} / {@code RealmMemberDao#insert} and how
 * {@code RealmServiceImpl} catches {@link SQLIntegrityConstraintViolationException}
 * specifically around those calls). Callers should do the same here to tell
 * "someone else claimed this chunk a moment ago" apart from any other
 * persistence failure.
 */
public interface RealmClaimDao {

    /**
     * Inserts {@code claim} and returns the generated {@code
     * realm_claims.id}. {@code claim.id()} is ignored on the way in. Throws
     * {@link SQLIntegrityConstraintViolationException} (a subtype of
     * {@link SQLException}, uncaught here) if {@code uq_chunk} rejects the
     * insert because the chunk was claimed a moment ago by someone else.
     */
    long insert(Connection connection, RealmClaim claim) throws SQLException;

    /** Looks up every claim currently owned by a realm. */
    List<RealmClaim> findByRealm(Connection connection, long realmId) throws SQLException;

    /** Counts how many chunks a realm currently has claimed. */
    int countByRealm(Connection connection, long realmId) throws SQLException;

    /**
     * Loads every claim in the table, across every realm. No bulk "find
     * all" query is normally exposed by a DAO in this project — every other
     * method here scopes to a single realm — but cache/warm-up at startup is
     * the sanctioned exception, the same reasoning {@code RealmCache}'s own
     * Javadoc documents for its {@code loadAll(...)}. This method exists
     * purely to back {@code RealmCache#loadClaims}.
     */
    List<RealmClaim> findAll(Connection connection) throws SQLException;

    /**
     * Deletes one claimed chunk belonging to {@code realmId}.
     *
     * @return {@code true} if a row was actually removed, {@code false} if
     *         no such claim existed (already gone, wrong realm, or never
     *         claimed).
     */
    boolean delete(Connection connection, long realmId, String world, int chunkX, int chunkZ) throws SQLException;
}
