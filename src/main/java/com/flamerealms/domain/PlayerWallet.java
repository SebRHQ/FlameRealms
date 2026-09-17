package com.flamerealms.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A player's personal wallet balance.
 *
 * @param playerUuid   the wallet owner's UUID ({@code player_wallets.player_uuid})
 * @param balanceCents the wallet balance, in whole cents
 *                     ({@code player_wallets.balance_cents})
 * @param updatedAt    when the balance was last changed
 *                     ({@code player_wallets.updated_at})
 */
public record PlayerWallet(
        UUID playerUuid,
        long balanceCents,
        Instant updatedAt
) {
}
