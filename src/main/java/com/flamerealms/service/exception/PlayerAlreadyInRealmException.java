package com.flamerealms.service.exception;

import java.util.UUID;

/**
 * Thrown when a player who already belongs to a realm attempts an action
 * that requires them not to — creating or joining another one. The
 * {@code realm_members.player_uuid} unique constraint ({@code
 * uq_player_one_realm}) is the database-level backstop this maps onto when a
 * race is lost between a cache pre-check and the insert committing.
 */
public final class PlayerAlreadyInRealmException extends RealmServiceException {

    private final UUID player;

    public PlayerAlreadyInRealmException(UUID player) {
        super("Player " + player + " already belongs to a realm");
        this.player = player;
    }

    public PlayerAlreadyInRealmException(UUID player, Throwable cause) {
        super("Player " + player + " already belongs to a realm", cause);
        this.player = player;
    }

    public UUID player() {
        return player;
    }
}
