package com.flamerealms.service.fake;

import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.domain.TransactionRecord;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.PlayerWalletDao;
import com.flamerealms.persistence.dao.RealmDao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Hand-written, in-memory {@link LedgerDao} test double. Deliberately
 * mirrors {@code JdbcLedgerDao}'s own composition and direction-convention
 * logic exactly (see {@link LedgerDao}'s class Javadoc) rather than
 * reimplementing it from scratch, since the whole point under test is "a
 * balance mutation and its ledger row always happen together, never one
 * without the other" — a fake with different logic here would test nothing
 * meaningful.
 *
 * <p>Composes a {@link PlayerWalletDao}/{@link RealmDao} pair exactly like
 * {@code JdbcLedgerDao} does (constructor injection, same order), so tests
 * wire this up with the same {@link FakePlayerWalletDao}/{@link FakeRealmDao}
 * instances the service under test uses, and can assert on both the ledger
 * ({@link #entries()}) and the underlying balances through those same fakes.
 *
 * <p>Not thread-safe and not meant to be — each test constructs its own
 * fresh instance. The {@code Connection} parameter on every method is
 * accepted (to satisfy the interface) but ignored; this class does not
 * simulate transaction rollback (see {@code TreasuryServiceImplTest}'s
 * Javadoc for why that is fine for what this test suite needs).
 */
public final class FakeLedgerDao implements LedgerDao {

    private final PlayerWalletDao playerWalletDao;
    private final RealmDao realmDao;
    private final List<TransactionRecord> entries = new ArrayList<>();
    private long nextId = 1;

    public FakeLedgerDao(PlayerWalletDao playerWalletDao, RealmDao realmDao) {
        this.playerWalletDao = playerWalletDao;
        this.realmDao = realmDao;
    }

    @Override
    public long insertEntry(Connection connection, TransactionRecord record) {
        TransactionRecord inserted = new TransactionRecord(
                nextId++, record.ts(), record.category(), record.reason(),
                record.sourceType(), record.sourceId(), record.targetType(), record.targetId(),
                record.amountCents(), record.metadata());
        entries.add(inserted);
        return inserted.id();
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

    /** Every ledger row recorded so far, oldest first. Test-only inspection point. */
    public List<TransactionRecord> entries() {
        return Collections.unmodifiableList(entries);
    }

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
                0L, Instant.now(), category, reason, sourceType, sourceId, targetType, targetId,
                Math.abs(deltaCents), null);
    }
}
