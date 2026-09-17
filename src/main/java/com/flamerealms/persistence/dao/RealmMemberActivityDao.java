package com.flamerealms.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Data access for the {@code realm_member_activity} table.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — see
 * {@link RealmDao} for the composition contract this DAO shares with it.
 *
 * <p>{@code realm_member_activity}'s primary key is the composite
 * {@code (realm_id, player_uuid, activity_date)} (see {@code
 * V3__claims.sql}); {@link #recordPresence} upserts against it rather than
 * requiring the caller to know whether today's row already exists. The
 * table's {@code contributed} column is unused as of this milestone (see
 * {@code V3__claims.sql}'s comment on it) and is not touched here.
 */
public interface RealmMemberActivityDao {

    /**
     * Adds {@code minutesDelta} to the player's recorded online minutes for
     * {@code realmId} on {@code date}, upserting the row if this is their
     * first recorded activity for that day
     * ({@code INSERT ... ON DUPLICATE KEY UPDATE online_minutes = online_minutes + ?}).
     */
    void recordPresence(Connection connection, long realmId, UUID playerUuid, LocalDate date, int minutesDelta)
            throws SQLException;

    /**
     * Counts the realm's distinct members whose total {@code online_minutes}
     * across every {@code activity_date >= since} is at least {@code
     * presenceThresholdMinutes} — the "activePopulation" count the daily
     * upkeep formula needs.
     */
    int countActiveMembers(Connection connection, long realmId, LocalDate since, int presenceThresholdMinutes)
            throws SQLException;
}
