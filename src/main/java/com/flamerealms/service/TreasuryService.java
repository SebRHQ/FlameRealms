package com.flamerealms.service;

import com.flamerealms.domain.Money;
import com.flamerealms.domain.RealmPermission;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Realm-treasury economy use cases for M2's scope: read a realm's balance,
 * and the two player&lt;-&gt;realm transfers the project's economy needs
 * (a player depositing into, or withdrawing from, their own realm's
 * treasury). Player wallets alone live in {@link EconomyService} instead.
 *
 * <p>Realm-to-realm transfers (a war stake, a trade) and any other economy
 * operation needing a second realm, a claim, or a war are explicitly out of
 * scope here — they need context (an opposing realm, an active war) that
 * does not exist in the project yet. No method on this interface accepts a
 * second realm id.
 *
 * <p>Every method dispatches through {@code AsyncDatabaseExecutor.submit(...)}
 * under the hood and returns a {@link CompletableFuture} — never call
 * {@code .join()}/{@code .get()} on one of these from the Paper main thread;
 * apply the result via {@code Bukkit.getScheduler().runTask(...)} in a
 * continuation instead.
 *
 * <p><b>Lock ordering.</b> {@link #deposit} and {@link #withdraw} both touch
 * one player's wallet and one realm's treasury inside a single transaction.
 * Every method on this class adjusts the player's wallet before the realm's
 * treasury, in that fixed order — every time, regardless of which way the
 * money is actually flowing — matching the project's fixed-lock-ordering
 * rule for any operation that touches both kinds of row: two concurrent
 * transfers against the same player+realm pair that acquired those row
 * locks in opposite orders could otherwise deadlock.
 *
 * <p>{@link #deposit} and {@link #withdraw} both reject a non-positive
 * {@code amount} synchronously and immediately, by throwing
 * {@link IllegalArgumentException} directly from the call itself — never by
 * failing the returned future — before any work is ever dispatched to the
 * database executor, exactly like {@link EconomyService}.
 *
 * <p>A failed future completes exceptionally with a
 * {@code com.flamerealms.service.exception.EconomyServiceException} subtype
 * (an unexpected database failure) or a
 * {@code com.flamerealms.service.exception.RealmServiceException} subtype
 * (the realm does not exist, or {@code actor} lacks the required
 * permission) — never with a raw {@link java.sql.SQLException}. The two
 * ordinary, expected "can't do that" outcomes (insufficient player or realm
 * funds) are never exceptions at all; they are a {@code false} the caller is
 * expected to handle gracefully.
 */
public interface TreasuryService {

    /** The realm's current treasury balance. */
    CompletableFuture<Money> balanceOf(long realmId);

    /**
     * Transfers {@code amount} out of {@code actor}'s own wallet and into
     * {@code realmId}'s treasury — a {@code TRANSFER} ledger entry. Completes
     * with {@code false} — not exceptionally — if {@code actor}'s wallet
     * cannot cover {@code amount}; an ordinary, expected outcome.
     *
     * @throws IllegalArgumentException if {@code amount} is zero or negative
     */
    CompletableFuture<Boolean> deposit(long realmId, UUID actor, Money amount);

    /**
     * Transfers {@code amount} out of {@code realmId}'s treasury and into
     * {@code actor}'s own wallet — a {@code TRANSFER} ledger entry —
     * provided {@code actor} currently holds a rank in {@code realmId} that
     * carries {@link RealmPermission#WITHDRAW}. If that permission check
     * fails, the returned future fails immediately with a clear domain
     * exception and no balance is touched. If it passes, completes with
     * {@code false} — not exceptionally — if {@code realmId}'s treasury
     * cannot cover {@code amount}; an ordinary, expected outcome.
     *
     * @throws IllegalArgumentException if {@code amount} is zero or negative
     */
    CompletableFuture<Boolean> withdraw(long realmId, UUID actor, Money amount);
}
