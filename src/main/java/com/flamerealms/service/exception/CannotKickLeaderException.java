package com.flamerealms.service.exception;

import java.util.UUID;

/**
 * Thrown when {@code RealmService#kick} targets a realm's own leader. A
 * leader must transfer leadership or disband instead of being kicked — the
 * same asymmetry {@link LeaderCannotLeaveException} already enforces for a
 * leader trying to {@code leave()} rather than being removed by someone else.
 */
public final class CannotKickLeaderException extends RealmServiceException {

    private final UUID leader;

    public CannotKickLeaderException(UUID leader) {
        super("Player " + leader + " is that realm's leader and cannot be kicked"
                + " — transfer leadership or disband the realm instead");
        this.leader = leader;
    }

    public UUID leader() {
        return leader;
    }
}
