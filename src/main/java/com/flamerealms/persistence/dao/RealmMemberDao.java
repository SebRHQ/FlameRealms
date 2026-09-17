package com.flamerealms.persistence.dao;

import com.flamerealms.domain.RealmMember;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code realm_members} table.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — see
 * {@link RealmDao} for the composition contract this DAO shares with it.
 *
 * <p>{@code realm_members.player_uuid} carries a database-level unique
 * constraint ({@code uq_player_one_realm}): a player belongs to at most one
 * realm. {@link #insert} relies on that constraint to reject a second
 * membership row for the same player rather than checking it here.
 */
public interface RealmMemberDao {

    /** Inserts a new membership row. */
    void insert(Connection connection, RealmMember member) throws SQLException;

    /** Removes a player's membership in a realm. */
    void delete(Connection connection, long realmId, UUID playerUuid) throws SQLException;

    /**
     * Looks up the membership row for a player. At most one row can ever
     * exist for a given player, per {@code uq_player_one_realm}.
     */
    Optional<RealmMember> findByPlayerUuid(Connection connection, UUID playerUuid) throws SQLException;

    /** Changes a member's rank within their realm. */
    void updateRank(Connection connection, long realmId, UUID playerUuid, long rankId) throws SQLException;
}
