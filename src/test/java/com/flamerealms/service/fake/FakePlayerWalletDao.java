package com.flamerealms.service.fake;

import com.flamerealms.domain.PlayerWallet;
import com.flamerealms.persistence.dao.PlayerWalletDao;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hand-written, in-memory {@link PlayerWalletDao} test double. Mirrors
 * {@code JdbcPlayerWalletDao}'s behavior closely enough for
 * {@code EconomyServiceImpl}/{@code TreasuryServiceImpl} unit tests:
 * {@link #ensureExists} is a no-op once a wallet row "exists", and
 * {@link #tryAdjustBalance} is the same conditional
 * "only if it wouldn't go negative" update as the real
 * {@code UPDATE ... WHERE balance_cents + ? >= 0}, never a read-then-write.
 *
 * <p>Not thread-safe and not meant to be — each test constructs its own
 * fresh instance. The {@code Connection} parameter on every method is
 * accepted (to satisfy the interface) but ignored.
 */
public final class FakePlayerWalletDao implements PlayerWalletDao {

    private final Map<UUID, PlayerWallet> walletsByPlayer = new LinkedHashMap<>();

    @Override
    public Optional<PlayerWallet> findByPlayer(Connection connection, UUID playerUuid) {
        return Optional.ofNullable(walletsByPlayer.get(playerUuid));
    }

    @Override
    public void ensureExists(Connection connection, UUID playerUuid) {
        walletsByPlayer.computeIfAbsent(playerUuid, uuid -> new PlayerWallet(uuid, 0L, Instant.now()));
    }

    @Override
    public boolean tryAdjustBalance(Connection connection, UUID playerUuid, long deltaCents) {
        PlayerWallet existing = walletsByPlayer.get(playerUuid);
        if (existing == null) {
            return false;
        }
        long updated = existing.balanceCents() + deltaCents;
        if (updated < 0) {
            return false;
        }
        walletsByPlayer.put(playerUuid, new PlayerWallet(playerUuid, updated, Instant.now()));
        return true;
    }

    /** Test-only convenience: the player's current balance, or 0 if they have no wallet row yet. */
    public long balanceOf(UUID playerUuid) {
        PlayerWallet wallet = walletsByPlayer.get(playerUuid);
        return wallet == null ? 0L : wallet.balanceCents();
    }
}
