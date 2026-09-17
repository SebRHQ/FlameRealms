package com.flamerealms.service.exception;

import java.util.UUID;

/** Thrown when an action reserved for a realm's leader is attempted by anyone else. */
public final class NotRealmLeaderException extends RealmServiceException {

    private final UUID actor;
    private final long realmId;

    public NotRealmLeaderException(UUID actor, long realmId) {
        super("Player " + actor + " is not the leader of realm " + realmId);
        this.actor = actor;
        this.realmId = realmId;
    }

    public UUID actor() {
        return actor;
    }

    public long realmId() {
        return realmId;
    }
}
