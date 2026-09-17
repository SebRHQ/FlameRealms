package com.flamerealms.service.exception;

import java.util.UUID;

/**
 * Thrown when an operation requires a player to belong to a (specific)
 * realm and they don't — e.g. {@code leave()} on a player with no
 * membership, or {@code setRank()} targeting a player who is not a member
 * of the realm the caller is acting in.
 */
public final class PlayerNotInRealmException extends RealmServiceException {

    private final UUID player;

    public PlayerNotInRealmException(UUID player) {
        super("Player " + player + " does not belong to that realm");
        this.player = player;
    }

    public UUID player() {
        return player;
    }
}
