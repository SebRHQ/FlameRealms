package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.Realm;
import com.flamerealms.persistence.dao.RealmDao;
import com.flamerealms.util.UuidCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC {@link RealmDao} implementation. No ORM — hand-written
 * {@link PreparedStatement}s only, matching {@code V1__realm_core.sql}.
 */
public final class JdbcRealmDao implements RealmDao {

    private static final String INSERT =
            "INSERT INTO realms (name, display_name, leader_uuid, level, created_at, disbanded_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String FIND_BY_ID =
            "SELECT id, name, display_name, leader_uuid, level, created_at, disbanded_at "
                    + "FROM realms WHERE id = ?";

    private static final String FIND_BY_NAME =
            "SELECT id, name, display_name, leader_uuid, level, created_at, disbanded_at "
                    + "FROM realms WHERE name = ?";

    private static final String FIND_BY_PLAYER_UUID =
            "SELECT r.id, r.name, r.display_name, r.leader_uuid, r.level, r.created_at, r.disbanded_at "
                    + "FROM realms r "
                    + "JOIN realm_members m ON m.realm_id = r.id "
                    + "WHERE m.player_uuid = ?";

    private static final String MARK_DISBANDED =
            "UPDATE realms SET disbanded_at = ? WHERE id = ?";

    private static final String DELETE =
            "DELETE FROM realms WHERE id = ?";

    private static final String FIND_BALANCE =
            "SELECT balance_cents FROM realms WHERE id = ?";

    private static final String TRY_ADJUST_BALANCE =
            "UPDATE realms SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents + ? >= 0";

    private static final String FIND_UPKEEP_DEBT =
            "SELECT upkeep_debt_cents FROM realms WHERE id = ?";

    private static final String RESET_UPKEEP_DEBT =
            "UPDATE realms SET upkeep_debt_cents = 0 WHERE id = ?";

    private static final String INCREMENT_UPKEEP_DEBT =
            "UPDATE realms SET upkeep_debt_cents = upkeep_debt_cents + ? WHERE id = ?";

    @Override
    public Realm insert(Connection connection, Realm realm) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, realm.name());
            statement.setString(2, realm.displayName());
            statement.setBytes(3, UuidCodec.toBytes(realm.leaderUuid()));
            statement.setInt(4, realm.level());
            statement.setTimestamp(5, Timestamp.from(realm.createdAt()));
            setNullableTimestamp(statement, 6, realm.disbandedAt());

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                return new Realm(id, realm.name(), realm.displayName(), realm.leaderUuid(),
                        realm.level(), realm.createdAt(), realm.disbandedAt());
            }
        }
    }

    @Override
    public Optional<Realm> findById(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_ID)) {
            statement.setLong(1, realmId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Realm> findByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_NAME)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Realm> findByPlayerUuid(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_PLAYER_UUID)) {
            statement.setBytes(1, UuidCodec.toBytes(playerUuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public void markDisbanded(Connection connection, long realmId, Instant disbandedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_DISBANDED)) {
            statement.setTimestamp(1, Timestamp.from(disbandedAt));
            statement.setLong(2, realmId);
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setLong(1, realmId);
            statement.executeUpdate();
        }
    }

    @Override
    public long findBalance(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BALANCE)) {
            statement.setLong(1, realmId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("No realm with id " + realmId);
                }
                return resultSet.getLong("balance_cents");
            }
        }
    }

    @Override
    public boolean tryAdjustBalance(Connection connection, long realmId, long deltaCents) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_ADJUST_BALANCE)) {
            statement.setLong(1, deltaCents);
            statement.setLong(2, realmId);
            statement.setLong(3, deltaCents);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public long findUpkeepDebt(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_UPKEEP_DEBT)) {
            statement.setLong(1, realmId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("No realm with id " + realmId);
                }
                return resultSet.getLong("upkeep_debt_cents");
            }
        }
    }

    @Override
    public void resetUpkeepDebt(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RESET_UPKEEP_DEBT)) {
            statement.setLong(1, realmId);
            statement.executeUpdate();
        }
    }

    @Override
    public void incrementUpkeepDebt(Connection connection, long realmId, long deltaCents) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INCREMENT_UPKEEP_DEBT)) {
            statement.setLong(1, deltaCents);
            statement.setLong(2, realmId);
            statement.executeUpdate();
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant instant)
            throws SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(instant));
        }
    }

    private static Realm mapRow(ResultSet resultSet) throws SQLException {
        Timestamp disbandedAt = resultSet.getTimestamp("disbanded_at");
        return new Realm(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("display_name"),
                UuidCodec.fromBytes(resultSet.getBytes("leader_uuid")),
                resultSet.getInt("level"),
                resultSet.getTimestamp("created_at").toInstant(),
                disbandedAt == null ? null : disbandedAt.toInstant()
        );
    }
}
