package com.flamerealms.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A realm's core identity — M1 scope, plus the M4 nexus location.
 *
 * <p>Deliberately excludes balance/treasury (M2) and any other fields owned
 * by later milestones. Those arrive via their own {@code ALTER TABLE}
 * migrations and their own accessors on this record when the time comes —
 * never bolted on early.
 *
 * <p><b>Nullable-boxed nexus fields.</b> {@code nexusWorld}/{@code nexusX}/
 * {@code nexusY}/{@code nexusZ} are nullable boxed types, not sentinel
 * primitives, and are treated as a single all-or-nothing group: either all
 * four are {@code null} (no nexus set — every realm created before {@code
 * V4__nexus_and_management.sql}, since there is no backfill) or all four are
 * non-null (every realm created from M4 onward, which sets its nexus
 * atomically at creation time). Every other file in the project that
 * constructs a {@code Realm} follows this same convention.
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
 * @param nexusWorld  world name of the realm's nexus block ({@code
 *                    realms.nexus_world}), {@code null} if unset
 * @param nexusX      nexus block X coordinate ({@code realms.nexus_x}),
 *                    {@code null} if unset
 * @param nexusY      nexus block Y coordinate ({@code realms.nexus_y}),
 *                    {@code null} if unset
 * @param nexusZ      nexus block Z coordinate ({@code realms.nexus_z}),
 *                    {@code null} if unset
 */
public record Realm(
        long id,
        String name,
        String displayName,
        UUID leaderUuid,
        int level,
        Instant createdAt,
        Instant disbandedAt,
        String nexusWorld,
        Integer nexusX,
        Integer nexusY,
        Integer nexusZ
) {
}
