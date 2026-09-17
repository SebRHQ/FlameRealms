package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.domain.TransactionRecord;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.PlayerWalletDao;
import com.flamerealms.persistence.dao.RealmDao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/**
 * Plain JDBC {@link LedgerDao} implementation. No ORM — hand-written
 * {@link PreparedStatement}s only, matching {@code V2__economy.sql}.
 *
 * <p>See {@link LedgerDao}'s class Javadoc for the "no balance mutation
 * without a ledger entry, same transaction" rule this class exists to
 * enforce, and for the {@code deltaCents} direction convention.
 */
public final class JdbcLedgerDao implements LedgerDao {

    private static final String INSERT =
            "INSERT INTO transactions "
                    + "(ts, category, reason, source_type, source_id, target_type, target_id, amount_cents, metadata) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final PlayerWalletDao playerWalletDao;
    private final RealmDao realmDao;

    public JdbcLedgerDao(PlayerWalletDao playerWalletDao, RealmDao realmDao) {
        this.playerWalletDao = playerWalletDao;
        this.realmDao = realmDao;
    }

    @Override
    public long insertEntry(Connection connection, TransactionRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            statement.setTimestamp(1, Timestamp.from(record.ts()));
            statement.setString(2, record.category().name());
            statement.setString(3, record.reason());
            statement.setString(4, record.sourceType().name());
            setNullableString(statement, 5, record.sourceId());
            setNullableEnum(statement, 6, record.targetType());
            setNullableString(statement, 7, record.targetId());
            statement.setLong(8, record.amountCents());
            setNullableString(statement, 9, record.metadata());

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    @Override
    public boolean recordAndApplyToPlayer(
            Connection connection,
            UUID player,
            long deltaCents,
            TransactionCategory category,
            String reason,
            LedgerEntity counterpartyType,
            String counterpartyId
    ) throws SQLException {
        playerWalletDao.ensureExists(connection, player);
        if (!playerWalletDao.tryAdjustBalance(connection, player, deltaCents)) {
            return false;
        }

        String playerId = player.toString();
        TransactionRecord record = deltaCents < 0
                ? ledgerRow(category, reason, LedgerEntity.PLAYER, playerId, counterpartyType, counterpartyId,
                        deltaCents)
                : ledgerRow(category, reason, counterpartyType, counterpartyId, LedgerEntity.PLAYER, playerId,
                        deltaCents);

        insertEntry(connection, record);
        return true;
    }

    @Override
    public boolean recordAndApplyToRealm(
            Connection connection,
            long realmId,
            long deltaCents,
            TransactionCategory category,
            String reason,
            LedgerEntity counterpartyType,
            String counterpartyId
    ) throws SQLException {
        if (!realmDao.tryAdjustBalance(connection, realmId, deltaCents)) {
            return false;
        }

        String realmIdString = String.valueOf(realmId);
        TransactionRecord record = deltaCents < 0
                ? ledgerRow(category, reason, LedgerEntity.REALM, realmIdString, counterpartyType, counterpartyId,
                        deltaCents)
                : ledgerRow(category, reason, counterpartyType, counterpartyId, LedgerEntity.REALM, realmIdString,
                        deltaCents);

        insertEntry(connection, record);
        return true;
    }

    /**
     * Builds the {@link TransactionRecord} to insert, per the direction
     * convention in {@link LedgerDao}'s class Javadoc: {@code amountCents}
     * is always the absolute value of {@code deltaCents}, direction lives
     * entirely in which side is source vs. target.
     */
    private static TransactionRecord ledgerRow(
            TransactionCategory category,
            String reason,
            LedgerEntity sourceType,
            String sourceId,
            LedgerEntity targetType,
            String targetId,
            long deltaCents
    ) {
        return new TransactionRecord(
                0L,
                Instant.now(),
                category,
                reason,
                sourceType,
                sourceId,
                targetType,
                targetId,
                Math.abs(deltaCents),
                null
        );
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableEnum(PreparedStatement statement, int index, LedgerEntity value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.name());
        }
    }
}
