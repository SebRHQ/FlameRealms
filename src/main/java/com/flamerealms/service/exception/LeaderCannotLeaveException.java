package com.flamerealms.service.exception;

import java.util.UUID;

/**
 * Thrown when a realm's leader attempts to {@code leave()} rather than
 * transferring leadership or disbanding. Leadership transfer does not exist
 * yet in M1's scope, so today the only way out for a leader is
 * {@code disbandRealm(...)}.
 */
public final class LeaderCannotLeaveException extends RealmServiceException {

    private final UUID leader;
    private final long realmId;

    public LeaderCannotLeaveException(UUID leader, long realmId) {
        super("Leader " + leader + " of realm " + realmId
                + " must transfer leadership or disband the realm, not leave it");
        this.leader = leader;
        this.realmId = realmId;
    }

    public UUID leader() {
        return leader;
    }

    public long realmId() {
        return realmId;
    }
}
