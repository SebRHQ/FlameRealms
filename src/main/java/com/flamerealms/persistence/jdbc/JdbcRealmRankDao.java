package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.RealmRank;
import com.flamerealms.persistence.dao.RealmRankDao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain JDBC {@link RealmRankDao} implementation. No ORM — hand-written
 * {@link PreparedStatement}s only, matching {@code V1__realm_core.sql}.
 */
public final class JdbcRealmRankDao implements RealmRankDao {

    private static final String INSERT =
            "INSERT INTO realm_ranks (realm_id, name, priority, permissions, is_default) "
                    + "VALUES (?, ?, ?, ?, ?)";

    private static final String FIND_BY_REALM_AND_NAME =
            "SELECT id, realm_id, name, priority, permissions, is_default "
                    + "FROM realm_ranks WHERE realm_id = ? AND name = ?";

    private static final String FIND_DEFAULT_RANK =
            "SELECT id, realm_id, name, priority, permissions, is_default "
                    + "FROM realm_ranks WHERE realm_id = ? AND is_default = TRUE";

    private static final String FIND_BY_ID =
            "SELECT id, realm_id, name, priority, permissions, is_default "
                    + "FROM realm_ranks WHERE id = ?";

    private static final String FIND_ALL_BY_REALM =
            "SELECT id, realm_id, name, priority, permissions, is_default "
                    + "FROM realm_ranks WHERE realm_id = ? ORDER BY priority DESC";

    @Override
    public RealmRank insert(Connection connection, RealmRank rank) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, rank.realmId());
            statement.setString(2, rank.name());
            statement.setInt(3, rank.priority());
            statement.setLong(4, rank.permissions());
            statement.setBoolean(5, rank.isDefault());

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                return new RealmRank(id, rank.realmId(), rank.name(), rank.priority(),
                        rank.permissions(), rank.isDefault());
            }
        }
    }

    @Override
    public Optional<RealmRank> findByRealmAndName(Connection connection, long realmId, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_REALM_AND_NAME)) {
            statement.setLong(1, realmId);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<RealmRank> findDefaultRank(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_DEFAULT_RANK)) {
            statement.setLong(1, realmId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<RealmRank> findById(Connection connection, long rankId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_ID)) {
            statement.setLong(1, rankId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public List<RealmRank> findAllByRealm(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_ALL_BY_REALM)) {
            statement.setLong(1, realmId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RealmRank> ranks = new ArrayList<>();
                while (resultSet.next()) {
                    ranks.add(mapRow(resultSet));
                }
                return ranks;
            }
        }
    }

    private static RealmRank mapRow(ResultSet resultSet) throws SQLException {
        return new RealmRank(
                resultSet.getLong("id"),
                resultSet.getLong("realm_id"),
                resultSet.getString("name"),
                resultSet.getInt("priority"),
                resultSet.getLong("permissions"),
                resultSet.getBoolean("is_default")
        );
    }
}
