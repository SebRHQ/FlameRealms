package com.flamerealms.service.exception;

import com.flamerealms.domain.ChunkCoordinate;

/**
 * Thrown when a chunk cannot be claimed because it already belongs to some
 * realm's territory. This covers both the ordinary case — a synchronous
 * {@code RealmCache#ownerOf} lookup could have caught it — and the genuine
 * race {@code realm_claims}'s {@code uq_chunk} unique constraint exists to
 * guard against: two concurrent {@code purchaseClaim} transactions targeting
 * the same chunk, where the loser's {@code RealmClaimDao#insert} fails with a
 * {@link java.sql.SQLIntegrityConstraintViolationException} that gets
 * translated into this instead.
 */
public final class ChunkAlreadyClaimedException extends RealmServiceException {

    private final ChunkCoordinate coordinate;

    public ChunkAlreadyClaimedException(ChunkCoordinate coordinate) {
        super("Chunk " + coordinate + " is already claimed");
        this.coordinate = coordinate;
    }

    public ChunkAlreadyClaimedException(ChunkCoordinate coordinate, Throwable cause) {
        super("Chunk " + coordinate + " is already claimed", cause);
        this.coordinate = coordinate;
    }

    public ChunkCoordinate coordinate() {
        return coordinate;
    }
}
