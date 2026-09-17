package com.flamerealms.service.exception;

import java.util.UUID;

/**
 * Thrown when {@code join()} is called for a player who does not hold a
 * pending invite to that realm. Invites are in-memory only in M1 — see
 * {@code RealmServiceImpl}'s class Javadoc — so this also covers the case
 * where a previously valid invite was lost across a plugin restart.
 */
public final class NotInvitedException extends RealmServiceException {

    private final UUID player;
    private final long realmId;

    public NotInvitedException(UUID player, long realmId) {
        super("Player " + player + " has no pending invite to realm " + realmId);
        this.player = player;
        this.realmId = realmId;
    }

    public UUID player() {
        return player;
    }

    public long realmId() {
        return realmId;
    }
}
