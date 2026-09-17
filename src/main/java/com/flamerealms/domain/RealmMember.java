package com.flamerealms.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A player's membership in a realm.
 *
 * <p>{@code realm_members.player_uuid} carries a database-level unique
 * constraint ({@code uq_player_one_realm}): a player belongs to at most one
 * realm at a time. This record has no opinion of its own about that — the
 * constraint is enforced by the schema, not by application code.
 *
 * @param realmId    the realm this membership belongs to
 *                   ({@code realm_members.realm_id})
 * @param playerUuid the member's UUID ({@code realm_members.player_uuid})
 * @param rankId     the member's current rank id ({@code realm_members.rank_id})
 * @param joinedAt   when the player joined the realm
 *                   ({@code realm_members.joined_at})
 */
public record RealmMember(
        long realmId,
        UUID playerUuid,
        long rankId,
        Instant joinedAt
) {
}
