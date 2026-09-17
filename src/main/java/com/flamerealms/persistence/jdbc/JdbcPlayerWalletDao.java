package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.PlayerWallet;
import com.flamerealms.persistence.dao.PlayerWalletDao;
import com.flamerealms.util.UuidCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC {@link PlayerWalletDao} implementation. No ORM — hand-written
 * {@link PreparedStatement}s only, matching {@code V2__economy.sql}.
 */
public final class JdbcPlayerWalletDao implements PlayerWalletDao {

    private static final String FIND_BY_PLAYER =
            "SELECT player_uuid, balance_cents, updated_at FROM player_wallets WHERE player_uuid = ?";

    // ON DUPLICATE KEY UPDATE with a no-op assignment: inserts a zero-balance
    // row if absent, changes nothing if a row already exists.
    private static final String ENSURE_EXISTS =
            "INSERT INTO player_wallets (player_uuid, balance_cents, updated_at) VALUES (?, 0, ?) "
                    + "ON DUPLICATE KEY UPDATE player_uuid = player_uuid";

    private static final String TRY_ADJUST_BALANCE =
            "UPDATE player_wallets SET balance_cents = balance_cents + ?, updated_at = ? "
                    + "WHERE player_uuid = ? AND balance_cents + ? >= 0";

    @Override
    public Optional<PlayerWallet> findByPlayer(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_PLAYER)) {
            statement.setBytes(1, UuidCodec.toBytes(playerUuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public void ensureExists(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ENSURE_EXISTS)) {
            statement.setBytes(1, UuidCodec.toBytes(playerUuid));
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    @Override
    public boolean tryAdjustBalance(Connection connection, UUID playerUuid, long deltaCents) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_ADJUST_BALANCE)) {
            statement.setLong(1, deltaCents);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setBytes(3, UuidCodec.toBytes(playerUuid));
            statement.setLong(4, deltaCents);
            return statement.executeUpdate() > 0;
        }
    }

    private static PlayerWallet mapRow(ResultSet resultSet) throws SQLException {
        return new PlayerWallet(
                UuidCodec.fromBytes(resultSet.getBytes("player_uuid")),
                resultSet.getLong("balance_cents"),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
