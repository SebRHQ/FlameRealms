package com.flamerealms.domain;

/**
 * A single rank within a realm's hierarchy.
 *
 * @param id          surrogate primary key ({@code realm_ranks.id}); {@code 0}
 *                    or unset before the row has been inserted
 * @param realmId     the owning realm's id ({@code realm_ranks.realm_id})
 * @param name        rank name, unique within its realm
 *                    ({@code realm_ranks.name})
 * @param priority    ordering/seniority of the rank ({@code realm_ranks.priority})
 * @param permissions bitmask of {@link RealmPermission} bits granted to this
 *                     rank ({@code realm_ranks.permissions})
 * @param isDefault   whether newly joined members are placed in this rank
 *                     ({@code realm_ranks.is_default})
 */
public record RealmRank(
        long id,
        long realmId,
        String name,
        int priority,
        long permissions,
        boolean isDefault
) {
}
