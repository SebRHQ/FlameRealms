package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.RealmMember;
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.util.UuidCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC {@link RealmMemberDao} implementation. No ORM — hand-written
 * {@link PreparedStatement}s only, matching {@code V1__realm_core.sql}.
 */
public final class JdbcRealmMemberDao implements RealmMemberDao {

    private static final String INSERT =
            "INSERT INTO realm_members (realm_id, player_uuid, rank_id, joined_at) VALUES (?, ?, ?, ?)";

    private static final String DELETE =
            "DELETE FROM realm_members WHERE realm_id = ? AND player_uuid = ?";

    private static final String FIND_BY_PLAYER_UUID =
            "SELECT realm_id, player_uuid, rank_id, joined_at FROM realm_members WHERE player_uuid = ?";

    private static final String UPDATE_RANK =
            "UPDATE realm_members SET rank_id = ? WHERE realm_id = ? AND player_uuid = ?";

    @Override
    public void insert(Connection connection, RealmMember member) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setLong(1, member.realmId());
            statement.setBytes(2, UuidCodec.toBytes(member.playerUuid()));
            statement.setLong(3, member.rankId());
            statement.setTimestamp(4, Timestamp.from(member.joinedAt()));
            statement.executeUpdate();
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

    @Override
    public Optional<RealmMember> findByPlayerUuid(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_PLAYER_UUID)) {
            statement.setBytes(1, UuidCodec.toBytes(playerUuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public void updateRank(Connection connection, long realmId, UUID playerUuid, long rankId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_RANK)) {
            statement.setLong(1, rankId);
            statement.setLong(2, realmId);
            statement.setBytes(3, UuidCodec.toBytes(playerUuid));
            statement.executeUpdate();
        }
    }

    private static RealmMember mapRow(ResultSet resultSet) throws SQLException {
        return new RealmMember(
                resultSet.getLong("realm_id"),
                UuidCodec.fromBytes(resultSet.getBytes("player_uuid")),
                resultSet.getLong("rank_id"),
                resultSet.getTimestamp("joined_at").toInstant()
        );
    }
}
