package com.flamerealms.service.exception;

import com.flamerealms.domain.RealmPermission;

import java.util.UUID;

/** Thrown when an actor's rank does not carry the {@link RealmPermission} an action requires. */
public final class MissingPermissionException extends RealmServiceException {

    private final UUID actor;
    private final RealmPermission permission;

    public MissingPermissionException(UUID actor, RealmPermission permission) {
        super("Player " + actor + " lacks the " + permission + " permission");
        this.actor = actor;
        this.permission = permission;
    }

    public UUID actor() {
        return actor;
    }

    public RealmPermission permission() {
        return permission;
    }
}
