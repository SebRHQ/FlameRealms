package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.RealmClaim;
import com.flamerealms.persistence.dao.RealmClaimDao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain JDBC {@link RealmClaimDao} implementation. No ORM — hand-written
 * {@link PreparedStatement}s only, matching {@code V3__claims.sql}.
 */
public final class JdbcRealmClaimDao implements RealmClaimDao {

    private static final String INSERT =
            "INSERT INTO realm_claims (realm_id, world, chunk_x, chunk_z, claimed_at, price_paid_cents) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String FIND_BY_REALM =
            "SELECT id, realm_id, world, chunk_x, chunk_z, claimed_at, price_paid_cents "
                    + "FROM realm_claims WHERE realm_id = ?";

    private static final String COUNT_BY_REALM =
            "SELECT COUNT(*) FROM realm_claims WHERE realm_id = ?";

    private static final String FIND_ALL =
            "SELECT id, realm_id, world, chunk_x, chunk_z, claimed_at, price_paid_cents FROM realm_claims";

    private static final String DELETE =
            "DELETE FROM realm_claims WHERE realm_id = ? AND world = ? AND chunk_x = ? AND chunk_z = ?";

    @Override
    public long insert(Connection connection, RealmClaim claim) throws SQLException {
        // uq_chunk's SQLIntegrityConstraintViolationException, if the insert
        // loses a race, is intentionally left uncaught here — see this DAO's
        // class Javadoc.
        try (PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, claim.realmId());
            statement.setString(2, claim.world());
            statement.setInt(3, claim.chunkX());
            statement.setInt(4, claim.chunkZ());
            statement.setTimestamp(5, Timestamp.from(claim.claimedAt()));
            statement.setLong(6, claim.pricePaidCents());

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    @Override
    public List<RealmClaim> findByRealm(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_REALM)) {
            statement.setLong(1, realmId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RealmClaim> claims = new ArrayList<>();
                while (resultSet.next()) {
                    claims.add(mapRow(resultSet));
                }
                return claims;
            }
        }
    }

    @Override
    public int countByRealm(Connection connection, long realmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COUNT_BY_REALM)) {
            statement.setLong(1, realmId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    @Override
    public List<RealmClaim> findAll(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_ALL);
             ResultSet resultSet = statement.executeQuery()) {
            List<RealmClaim> claims = new ArrayList<>();
            while (resultSet.next()) {
                claims.add(mapRow(resultSet));
            }
            return claims;
        }
    }

    @Override
    public boolean delete(Connection connection, long realmId, String world, int chunkX, int chunkZ)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setLong(1, realmId);
            statement.setString(2, world);
            statement.setInt(3, chunkX);
            statement.setInt(4, chunkZ);
            return statement.executeUpdate() > 0;
        }
    }

    private static RealmClaim mapRow(ResultSet resultSet) throws SQLException {
        return new RealmClaim(
                resultSet.getLong("id"),
                resultSet.getLong("realm_id"),
                resultSet.getString("world"),
                resultSet.getInt("chunk_x"),
                resultSet.getInt("chunk_z"),
                resultSet.getTimestamp("claimed_at").toInstant(),
                resultSet.getLong("price_paid_cents")
        );
    }
}
