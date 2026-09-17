package com.flamerealms.service.fake;

import com.flamerealms.domain.RealmMember;
import com.flamerealms.persistence.dao.RealmMemberDao;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hand-written, in-memory {@link RealmMemberDao} test double, keyed by
 * player UUID to mirror the real {@code uq_player_one_realm} constraint: a
 * player can have at most one membership row at a time.
 */
public final class FakeRealmMemberDao implements RealmMemberDao {

    private final Map<UUID, RealmMember> membersByPlayer = new LinkedHashMap<>();

    @Override
    public void insert(Connection connection, RealmMember member) throws SQLException {
        if (membersByPlayer.containsKey(member.playerUuid())) {
            throw new SQLIntegrityConstraintViolationException(
                    "Duplicate entry for key 'uq_player_one_realm'");
        }
        membersByPlayer.put(member.playerUuid(), member);
    }

    @Override
    public void delete(Connection connection, long realmId, UUID playerUuid) {
        RealmMember existing = membersByPlayer.get(playerUuid);
        if (existing != null && existing.realmId() == realmId) {
            membersByPlayer.remove(playerUuid);
        }
    }

    @Override
    public Optional<RealmMember> findByPlayerUuid(Connection connection, UUID playerUuid) {
        return Optional.ofNullable(membersByPlayer.get(playerUuid));
    }

    @Override
    public void updateRank(Connection connection, long realmId, UUID playerUuid, long rankId) {
        RealmMember existing = membersByPlayer.get(playerUuid);
        if (existing != null && existing.realmId() == realmId) {
            membersByPlayer.put(playerUuid,
                    new RealmMember(existing.realmId(), existing.playerUuid(), rankId, existing.joinedAt()));
        }
    }
}
