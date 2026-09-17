package com.flamerealms.service.exception;

import com.flamerealms.domain.ChunkCoordinate;

/**
 * Thrown by {@code ClaimService#purchaseClaim} when the requested chunk is
 * not {@link ChunkCoordinate#isAdjacentTo} any chunk the realm already has
 * claimed, and the realm already owns at least one claim (a realm's very
 * first claim always "seeds" its territory instead — see {@code
 * ClaimServiceImpl}'s class Javadoc).
 *
 * <p>Contiguity is checked purely against the realm's existing claims, never
 * against a Nexus location: M2 has no Nexus concept yet (it arrives in M4),
 * so there is nothing else to anchor against.
 */
public final class ClaimNotContiguousException extends RealmServiceException {

    private final ChunkCoordinate coordinate;

    public ClaimNotContiguousException(ChunkCoordinate coordinate) {
        super("Chunk " + coordinate + " is not adjacent to any existing claim of this realm");
        this.coordinate = coordinate;
    }

    public ChunkCoordinate coordinate() {
        return coordinate;
    }
}
