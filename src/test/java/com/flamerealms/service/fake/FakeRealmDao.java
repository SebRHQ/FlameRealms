package com.flamerealms.service.fake;

import com.flamerealms.domain.Realm;
import com.flamerealms.persistence.dao.RealmDao;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hand-written, in-memory {@link RealmDao} test double. Mirrors the
 * {@code realms} table's real constraints closely enough for
 * {@code RealmServiceImpl} unit tests: a unique {@code name} column
 * (rejected the same way {@code JdbcRealmDao} would see it rejected by
 * MariaDB — a {@link SQLIntegrityConstraintViolationException}).
 *
 * <p>Not thread-safe and not meant to be — each test constructs its own
 * fresh instance. The {@code Connection} parameter on every method is
 * accepted (to satisfy the interface) but ignored.
 */
public final class FakeRealmDao implements RealmDao {

    private final Map<Long, Realm> realmsById = new LinkedHashMap<>();
    private final Map<Long, Long> balancesById = new LinkedHashMap<>();
    private final Map<Long, Long> upkeepDebtById = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public Realm insert(Connection connection, Realm realm) throws SQLException {
        for (Realm existing : realmsById.values()) {
            if (existing.name().equals(realm.name())) {
                throw new SQLIntegrityConstraintViolationException(
                        "Duplicate entry '" + realm.name() + "' for key 'realms.name'");
            }
        }

        Realm inserted = new Realm(
                nextId++, realm.name(), realm.displayName(), realm.leaderUuid(),
                realm.level(), realm.createdAt(), realm.disbandedAt());
        realmsById.put(inserted.id(), inserted);
        balancesById.put(inserted.id(), 0L);
        upkeepDebtById.put(inserted.id(), 0L);
        return inserted;
    }

    @Override
    public Optional<Realm> findById(Connection connection, long realmId) {
        return Optional.ofNullable(realmsById.get(realmId));
    }

    @Override
    public Optional<Realm> findByName(Connection connection, String name) {
        return realmsById.values().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    @Override
    public Optional<Realm> findByPlayerUuid(Connection connection, UUID playerUuid) {
        // Not exercised by RealmServiceImpl (RealmCache serves that read
        // path), so a linear scan with no membership join is fine here —
        // no test needs it, but the interface still requires an
        // implementation.
        return Optional.empty();
    }

    @Override
    public void markDisbanded(Connection connection, long realmId, Instant disbandedAt) {
        Realm existing = realmsById.get(realmId);
        if (existing != null) {
            realmsById.put(realmId, new Realm(
                    existing.id(), existing.name(), existing.displayName(), existing.leaderUuid(),
                    existing.level(), existing.createdAt(), disbandedAt));
        }
    }

    @Override
    public void delete(Connection connection, long realmId) {
        realmsById.remove(realmId);
        balancesById.remove(realmId);
        upkeepDebtById.remove(realmId);
    }

    @Override
    public long findBalance(Connection connection, long realmId) {
        Long balance = balancesById.get(realmId);
        if (balance == null) {
            throw new IllegalStateException("No realm with id " + realmId);
        }
        return balance;
    }

    @Override
    public boolean tryAdjustBalance(Connection connection, long realmId, long deltaCents) {
        Long balance = balancesById.get(realmId);
        if (balance == null) {
            return false;
        }
        long updated = balance + deltaCents;
        if (updated < 0) {
            return false;
        }
        balancesById.put(realmId, updated);
        return true;
    }

    @Override
    public long findUpkeepDebt(Connection connection, long realmId) {
        Long debt = upkeepDebtById.get(realmId);
        if (debt == null) {
            throw new IllegalStateException("No realm with id " + realmId);
        }
        return debt;
    }

    @Override
    public void resetUpkeepDebt(Connection connection, long realmId) {
        if (upkeepDebtById.containsKey(realmId)) {
            upkeepDebtById.put(realmId, 0L);
        }
    }

    @Override
    public void incrementUpkeepDebt(Connection connection, long realmId, long deltaCents) {
        Long current = upkeepDebtById.get(realmId);
        if (current != null) {
            upkeepDebtById.put(realmId, current + deltaCents);
        }
    }
}
