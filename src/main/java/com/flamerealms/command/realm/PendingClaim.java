package com.flamerealms.command.realm;

import java.time.Instant;

/**
 * A player's in-memory, unconfirmed claim preview created by {@code /realm
 * claim} and consumed by {@code /realm claim confirm}. Held only in {@link
 * RealmCommand}'s own {@code ConcurrentHashMap<UUID, PendingClaim>} field —
 * never persisted, never touches the database, and expresses no opinion of
 * its own about whether the claim would actually succeed. {@code
 * ClaimService#purchaseClaim} re-validates permission, contiguity and
 * treasury funds for real at confirm time; this record exists purely so the
 * player doesn't have to repeat their target chunk on confirm and so a stale
 * preview (the realm's claims — and therefore the price — may have changed
 * since) can't be confirmed indefinitely after the fact.
 *
 * @param realmId   realm the previewed claim would belong to
 * @param world     Bukkit world name of the previewed chunk
 * @param chunkX    chunk X coordinate of the previewed chunk
 * @param chunkZ    chunk Z coordinate of the previewed chunk
 * @param expiresAt instant after which this preview is stale and {@code
 *                  /realm claim confirm} must reject it instead of calling
 *                  {@code ClaimService} at all
 */
public record PendingClaim(long realmId, String world, int chunkX, int chunkZ, Instant expiresAt) {

    /** Whether this pending claim's preview is stale as of now. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
