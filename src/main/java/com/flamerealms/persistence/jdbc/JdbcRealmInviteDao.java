package com.flamerealms.persistence.jdbc;

import com.flamerealms.persistence.dao.RealmInviteDao;
import com.flamerealms.util.UuidCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Plain JDBC {@link RealmInviteDao} implementation. No ORM — hand-written
 * {@link PreparedStatement}s only, matching {@code V4__nexus_and_management.sql}.
 */
public final class JdbcRealmInviteDao implements RealmInviteDao {

    private static final String INSERT =
            "INSERT INTO realm_invites (realm_id, player_uuid, invited_at) "
                    + "VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE invited_at = VALUES(invited_at)";

    private static final String EXISTS =
            "SELECT 1 FROM realm_invites WHERE realm_id = ? AND player_uuid = ?";

    private static final String DELETE =
            "DELETE FROM realm_invites WHERE realm_id = ? AND player_uuid = ?";

    @Override
    public void insert(Connection connection, long realmId, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setLong(1, realmId);
            statement.setBytes(2, UuidCodec.toBytes(playerUuid));
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    @Override
    public boolean exists(Connection connection, long realmId, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXISTS)) {
            statement.setLong(1, realmId);
            statement.setBytes(2, UuidCodec.toBytes(playerUuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Override
    public void delete(Connection connection, long realmId, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setLong(1, realmId);
            statement.setBytes(2, UuidCodec.toBytes(playerUuid));
            statement.executeUpdate();
        }
    }
}
