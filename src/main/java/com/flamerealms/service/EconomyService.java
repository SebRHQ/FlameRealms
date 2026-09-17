package com.flamerealms.service;

import com.flamerealms.domain.Money;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Player-wallet economy use cases for M2's scope: read a player's balance,
 * and credit/debit it with a ledger-audited transaction. Realm treasuries
 * and player&lt;-&gt;realm transfers live in {@link TreasuryService} instead;
 * this interface never touches a realm.
 *
 * <p>Every method dispatches through {@code AsyncDatabaseExecutor.submit(...)}
 * under the hood and returns a {@link CompletableFuture} — never call
 * {@code .join()}/{@code .get()} on one of these from the Paper main thread;
 * apply the result via {@code Bukkit.getScheduler().runTask(...)} in a
 * continuation instead.
 *
 * <p>{@link #deposit} and {@link #withdraw} both reject a non-positive
 * {@code amount} synchronously and immediately, by throwing
 * {@link IllegalArgumentException} directly from the call itself — never by
 * failing the returned future — before any work is ever dispatched to the
 * database executor.
 *
 * <p>A failed future completes exceptionally with a
 * {@code com.flamerealms.service.exception.EconomyServiceException} subtype
 * wrapping whatever unexpected failure occurred — never with a raw
 * {@link java.sql.SQLException}. The two ordinary, expected "can't do that"
 * outcomes below (insufficient funds) are never exceptions at all; they are
 * a {@code false} the caller is expected to handle gracefully.
 */
public interface EconomyService {

    /**
     * The player's current wallet balance. {@link Money#ZERO} if the player
     * has no {@code player_wallets} row yet (i.e. they have never had a
     * balance-mutating economy interaction).
     */
    CompletableFuture<Money> balanceOf(UUID player);

    /**
     * Credits {@code player}'s wallet by {@code amount} — a {@code FAUCET}
     * ledger entry (source {@code SERVER}, target {@code PLAYER:player}).
     * Crediting money cannot fail on insufficient funds, so this completes
     * with {@code true} in the ordinary case; it completes exceptionally
     * only on a genuine, unexpected database failure.
     *
     * @throws IllegalArgumentException if {@code amount} is zero or negative
     */
    CompletableFuture<Boolean> deposit(UUID player, Money amount, String reason);

    /**
     * Debits {@code player}'s wallet by {@code amount} — a {@code SINK}
     * ledger entry (source {@code PLAYER:player}, target {@code SERVER}).
     * Completes with {@code false} — not exceptionally — if the player's
     * balance cannot cover {@code amount}; this is an ordinary, expected
     * outcome the caller is expected to handle gracefully, not a failure
     * mode.
     *
     * @throws IllegalArgumentException if {@code amount} is zero or negative
     */
    CompletableFuture<Boolean> withdraw(UUID player, Money amount, String reason);
}
