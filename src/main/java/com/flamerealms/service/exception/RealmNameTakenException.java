package com.flamerealms.service.exception;

/**
 * Thrown when a realm name is already in use — either caught early against
 * {@code RealmCache} for fast feedback, or surfaced from the {@code realms}
 * table's unique constraint on {@code name} when a race is lost between the
 * cache pre-check and the insert actually committing.
 */
public final class RealmNameTakenException extends RealmServiceException {

    private final String name;

    public RealmNameTakenException(String name) {
        super("Realm name '" + name + "' is already taken");
        this.name = name;
    }

    public RealmNameTakenException(String name, Throwable cause) {
        super("Realm name '" + name + "' is already taken", cause);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
