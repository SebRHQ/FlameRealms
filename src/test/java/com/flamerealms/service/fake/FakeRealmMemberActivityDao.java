package com.flamerealms.service.fake;

import com.flamerealms.persistence.dao.RealmMemberActivityDao;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hand-written, in-memory {@link RealmMemberActivityDao} test double,
 * mirroring the real {@code realm_member_activity} table's composite-key
 * upsert behavior ({@code recordPresence} adds onto an existing
 * {@code (realmId, playerUuid, date)} row rather than replacing it).
 *
 * <p>Not thread-safe and not meant to be — each test constructs its own
 * fresh instance. The {@code Connection} parameter on every method is
 * accepted (to satisfy the interface) but ignored.
 */
public final class FakeRealmMemberActivityDao implements RealmMemberActivityDao {

    private record Key(long realmId, UUID playerUuid, LocalDate date) {
    }

    private final Map<Key, Integer> minutesByKey = new LinkedHashMap<>();

    // realm id -> player uuid -> total online minutes across every recorded
    // date, regardless of `since` — countActiveMembers below filters on the
    // per-call `since`/threshold, this just needs a per-player running total
    // per date to sum over.
    @Override
    public void recordPresence(Connection connection, long realmId, UUID playerUuid, LocalDate date, int minutesDelta) {
        minutesByKey.merge(new Key(realmId, playerUuid, date), minutesDelta, Integer::sum);
    }

    @Override
    public int countActiveMembers(Connection connection, long realmId, LocalDate since, int presenceThresholdMinutes) {
        Map<UUID, Integer> totalsByPlayer = new LinkedHashMap<>();
        for (Map.Entry<Key, Integer> entry : minutesByKey.entrySet()) {
            Key key = entry.getKey();
            if (key.realmId() == realmId && !key.date().isBefore(since)) {
                totalsByPlayer.merge(key.playerUuid(), entry.getValue(), Integer::sum);
            }
        }
        int count = 0;
        for (int total : totalsByPlayer.values()) {
            if (total >= presenceThresholdMinutes) {
                count++;
            }
        }
        return count;
    }

    /** Test-only inspection point: total recorded minutes for a player, on a given realm/date. */
    public int minutesFor(long realmId, UUID playerUuid, LocalDate date) {
        return minutesByKey.getOrDefault(new Key(realmId, playerUuid, date), 0);
    }
}
