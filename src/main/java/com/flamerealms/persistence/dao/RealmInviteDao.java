package com.flamerealms.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for the {@code realm_invites} table.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — see
 * {@link RealmDao} for the composition contract this DAO shares with it.
 */
public interface RealmInviteDao {

    /**
     * Inserts an invite for {@code playerUuid} to {@code realmId}, or, if
     * that exact (realm, player) pair is already invited, refreshes its
     * {@code invited_at} timestamp instead ({@code INSERT ... ON DUPLICATE
     * KEY UPDATE invited_at = VALUES(invited_at)}). Re-inviting an
     * already-invited player is not an error.
     */
    void insert(Connection connection, long realmId, UUID playerUuid) throws SQLException;

    /** Whether this exact (realm, player) pair is currently invited. */
    boolean exists(Connection connection, long realmId, UUID playerUuid) throws SQLException;

    /**
     * Removes one invite. Called once it has been consumed by a join; could
     * also back an explicit "uninvite" later, though nothing does yet.
     */
    void delete(Connection connection, long realmId, UUID playerUuid) throws SQLException;
}
