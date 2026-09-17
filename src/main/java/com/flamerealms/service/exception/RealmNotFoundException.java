package com.flamerealms.service.exception;

/** Thrown when an operation targets a realm id that does not (or no longer) exist. */
public final class RealmNotFoundException extends RealmServiceException {

    private final long realmId;

    public RealmNotFoundException(long realmId) {
        super("Realm " + realmId + " does not exist");
        this.realmId = realmId;
    }

    public long realmId() {
        return realmId;
    }
}
