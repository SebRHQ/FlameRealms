package com.flamerealms.service.fake;

import com.flamerealms.persistence.dao.RealmInviteDao;

import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Hand-written, in-memory {@link RealmInviteDao} test double, keyed by
 * (realm, player) pair. Not thread-safe and not meant to be — each test
 * constructs its own fresh instance. The {@code Connection} parameter on
 * every method is accepted (to satisfy the interface) but ignored.
 */
public final class FakeRealmInviteDao implements RealmInviteDao {

    private record InviteKey(long realmId, UUID playerUuid) {
    }

    private final Set<InviteKey> invites = new HashSet<>();

    @Override
    public void insert(Connection connection, long realmId, UUID playerUuid) {
        invites.add(new InviteKey(realmId, playerUuid));
    }

    @Override
    public boolean exists(Connection connection, long realmId, UUID playerUuid) {
        return invites.contains(new InviteKey(realmId, playerUuid));
    }

    @Override
    public void delete(Connection connection, long realmId, UUID playerUuid) {
        invites.remove(new InviteKey(realmId, playerUuid));
    }
}
