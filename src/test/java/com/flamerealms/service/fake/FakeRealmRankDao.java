package com.flamerealms.service.fake;

import com.flamerealms.domain.RealmRank;
import com.flamerealms.persistence.dao.RealmRankDao;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written, in-memory {@link RealmRankDao} test double, mirroring the
 * real {@code uq_realm_rank_name} constraint ({@code realm_id, name}).
 */
public final class FakeRealmRankDao implements RealmRankDao {

    private final Map<Long, RealmRank> ranksById = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public RealmRank insert(Connection connection, RealmRank rank) throws SQLException {
        for (RealmRank existing : ranksById.values()) {
            if (existing.realmId() == rank.realmId() && existing.name().equals(rank.name())) {
                throw new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'uq_realm_rank_name'");
            }
        }

        RealmRank inserted = new RealmRank(
                nextId++, rank.realmId(), rank.name(), rank.priority(), rank.permissions(), rank.isDefault());
        ranksById.put(inserted.id(), inserted);
        return inserted;
    }

    @Override
    public Optional<RealmRank> findByRealmAndName(Connection connection, long realmId, String name) {
        return ranksById.values().stream()
                .filter(r -> r.realmId() == realmId && r.name().equals(name))
                .findFirst();
    }

    @Override
    public Optional<RealmRank> findDefaultRank(Connection connection, long realmId) {
        return ranksById.values().stream()
                .filter(r -> r.realmId() == realmId && r.isDefault())
                .findFirst();
    }

    @Override
    public Optional<RealmRank> findById(Connection connection, long rankId) {
        return Optional.ofNullable(ranksById.get(rankId));
    }
}
