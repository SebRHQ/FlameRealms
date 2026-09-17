package com.flamerealms.domain;

import java.time.Instant;

/**
 * A single claimed chunk belonging to a realm.
 *
 * <p>Plain data only — no Bukkit/JDBC types. {@code world} is a Bukkit world
 * name (as returned by {@code World#getName()}), compared/stored exactly as
 * given: case-sensitive, no normalization.
 *
 * @param id             surrogate primary key ({@code realm_claims.id}); {@code 0}
 *                        or unset before the row has been inserted
 * @param realmId        owning realm's id ({@code realm_claims.realm_id})
 * @param world          Bukkit world name the chunk belongs to ({@code realm_claims.world})
 * @param chunkX         chunk X coordinate ({@code realm_claims.chunk_x})
 * @param chunkZ         chunk Z coordinate ({@code realm_claims.chunk_z})
 * @param claimedAt      claim timestamp ({@code realm_claims.claimed_at})
 * @param pricePaidCents price paid for this claim, in cents ({@code realm_claims.price_paid_cents})
 */
public record RealmClaim(
        long id,
        long realmId,
        String world,
        int chunkX,
        int chunkZ,
        Instant claimedAt,
        long pricePaidCents
) {

    /** This claim's chunk coordinate, independent of which realm owns it. */
    public ChunkCoordinate coordinate() {
        return new ChunkCoordinate(world, chunkX, chunkZ);
    }
}
