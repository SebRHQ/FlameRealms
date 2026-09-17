package com.flamerealms.persistence.jdbc;

import com.flamerealms.persistence.dao.RealmMemberActivityDao;
import com.flamerealms.util.UuidCodec;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Plain JDBC {@link RealmMemberActivityDao} implementation. No ORM —
 * hand-written {@link PreparedStatement}s only, matching {@code
 * V3__claims.sql}.
 */
public final class JdbcRealmMemberActivityDao implements RealmMemberActivityDao {

    // Upserts against the (realm_id, player_uuid, activity_date) primary
    // key: inserts today's row with minutesDelta as a starting value, or
    // adds minutesDelta onto an existing row's online_minutes.
    private static final String RECORD_PRESENCE =
            "INSERT INTO realm_member_activity (realm_id, player_uuid, activity_date, online_minutes) "
                    + "VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE online_minutes = online_minutes + ?";

    // Sums each member's online_minutes across every activity_date since the
    // cutoff, keeps only those whose total clears the threshold, and counts
    // the resulting groups — one query, no read-then-filter-in-Java.
    private static final String COUNT_ACTIVE_MEMBERS =
            "SELECT COUNT(*) FROM ("
                    + "SELECT player_uuid FROM realm_member_activity "
                    + "WHERE realm_id = ? AND activity_date >= ? "
                    + "GROUP BY player_uuid "
                    + "HAVING SUM(online_minutes) >= ?"
                    + ") AS active_members";

    @Override
    public void recordPresence(Connection connection, long realmId, UUID playerUuid, LocalDate date, int minutesDelta)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RECORD_PRESENCE)) {
            statement.setLong(1, realmId);
            statement.setBytes(2, UuidCodec.toBytes(playerUuid));
            statement.setDate(3, Date.valueOf(date));
            statement.setInt(4, minutesDelta);
            statement.setInt(5, minutesDelta);
            statement.executeUpdate();
        }
    }

    @Override
    public int countActiveMembers(Connection connection, long realmId, LocalDate since, int presenceThresholdMinutes)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COUNT_ACTIVE_MEMBERS)) {
            statement.setLong(1, realmId);
            statement.setDate(2, Date.valueOf(since));
            statement.setInt(3, presenceThresholdMinutes);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
