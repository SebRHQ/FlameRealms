package com.flamerealms.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A realm's core identity — M1 scope only.
 *
 * <p>Deliberately excludes balance/treasury (M2), nexus (M4), and any other
 * fields owned by later milestones. Those arrive via their own {@code ALTER
 * TABLE} migrations and their own accessors on this record when the time
 * comes — never bolted on early.
 *
 * @param id          surrogate primary key ({@code realms.id}); {@code 0} or
 *                    unset before the row has been inserted
 * @param name        unique, machine-facing identifier ({@code realms.name})
 * @param displayName player-facing name ({@code realms.display_name})
 * @param leaderUuid  UUID of the realm's leader ({@code realms.leader_uuid})
 * @param level       realm level, starts at {@code 1} ({@code realms.level})
 * @param createdAt   creation timestamp ({@code realms.created_at})
 * @param disbandedAt disbandment timestamp, {@code null} while active
 *                    ({@code realms.disbanded_at})
 */
public record Realm(
        long id,
        String name,
        String displayName,
        UUID leaderUuid,
        int level,
        Instant createdAt,
        Instant disbandedAt
) {
}
