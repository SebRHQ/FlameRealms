package com.flamerealms.persistence.dao;

import com.flamerealms.domain.RealmRank;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the {@code realm_ranks} table.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — see
 * {@link RealmDao} for the composition contract this DAO shares with it.
 */
public interface RealmRankDao {

    /**
     * Inserts {@code rank} and returns it with the generated {@code id}
     * populated. {@code rank.id()} is ignored on the way in.
     */
    RealmRank insert(Connection connection, RealmRank rank) throws SQLException;

    /** Looks up a rank by its realm and unique-within-that-realm name. */
    Optional<RealmRank> findByRealmAndName(Connection connection, long realmId, String name) throws SQLException;

    /** Looks up the rank newly joined members of a realm are placed in ({@code is_default = TRUE}). */
    Optional<RealmRank> findDefaultRank(Connection connection, long realmId) throws SQLException;

    /**
     * Looks up a rank by its surrogate id. Added in the RealmService stage
     * (not part of M1's original DAO contract) because {@code setRank}'s
     * permission check needs to resolve an actor's {@code
     * realm_members.rank_id} to that rank's {@code permissions} bitmask,
     * and neither existing lookup (by realm+name, or the default rank) can
     * do that from an id alone.
     */
    Optional<RealmRank> findById(Connection connection, long rankId) throws SQLException;

    /**
     * Every rank belonging to {@code realmId}, ordered by {@code priority
     * DESC} so Leader/Officer/Member (or any custom ranks) come out in their
     * natural hierarchy order, highest priority first. For a future
     * rank-picker UI/command to enumerate. Empty (never {@code null}) if the
     * realm has no ranks.
     */
    List<RealmRank> findAllByRealm(Connection connection, long realmId) throws SQLException;
}
