package com.flamerealms.service.fake;

import com.flamerealms.domain.RealmClaim;
import com.flamerealms.persistence.dao.RealmClaimDao;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written, in-memory {@link RealmClaimDao} test double. Not thread-safe
 * and not meant to be — each test constructs its own fresh instance. The
 * {@code Connection} parameter on every method is accepted (to satisfy the
 * interface) but ignored.
 */
public final class FakeRealmClaimDao implements RealmClaimDao {

    private final Map<Long, RealmClaim> claimsById = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public long insert(Connection connection, RealmClaim claim) {
        RealmClaim inserted = new RealmClaim(
                nextId++, claim.realmId(), claim.world(), claim.chunkX(), claim.chunkZ(),
                claim.claimedAt(), claim.pricePaidCents());
        claimsById.put(inserted.id(), inserted);
        return inserted.id();
    }

    @Override
    public List<RealmClaim> findByRealm(Connection connection, long realmId) {
        List<RealmClaim> result = new ArrayList<>();
        for (RealmClaim claim : claimsById.values()) {
            if (claim.realmId() == realmId) {
                result.add(claim);
            }
        }
        return result;
    }

    @Override
    public int countByRealm(Connection connection, long realmId) {
        return findByRealm(connection, realmId).size();
    }

    @Override
    public List<RealmClaim> findAll(Connection connection) {
        return new ArrayList<>(claimsById.values());
    }

    @Override
    public boolean delete(Connection connection, long realmId, String world, int chunkX, int chunkZ) {
        return claimsById.values().removeIf(claim ->
                claim.realmId() == realmId && claim.world().equals(world)
                        && claim.chunkX() == chunkX && claim.chunkZ() == chunkZ);
    }

    /** Test-only convenience: seeds {@code count} claims for a realm, at distinct chunk coordinates. */
    public void seedClaims(long realmId, int count) {
        for (int i = 0; i < count; i++) {
            insert(null, new RealmClaim(0L, realmId, "world", i, 0, java.time.Instant.now(), 0L));
        }
    }
}
