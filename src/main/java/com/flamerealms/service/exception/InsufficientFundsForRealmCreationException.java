package com.flamerealms.service.exception;

import java.util.UUID;

/**
 * Thrown by {@code RealmService#createRealm} when the prospective leader's
 * personal wallet cannot cover {@code PricingConfig#realmCreationFeeCents()}.
 *
 * <p>Unlike {@code TreasuryService#withdraw}/{@code #deposit} — which return a
 * plain {@code false} for the same underlying "can't cover it" condition, per
 * their own contract — {@code createRealm} returns a
 * {@code CompletableFuture<Realm>}, and there is no meaningful "successful
 * but empty" realm to hand back. So this one genuine, expected "can't afford
 * it" outcome is surfaced as a specific exception instead of a sentinel
 * value, the same way {@code ClaimService#purchaseClaim}'s own
 * {@link InsufficientTreasuryFundsException} handles the identical shape of
 * problem against a realm treasury rather than a personal wallet.
 */
public final class InsufficientFundsForRealmCreationException extends RealmServiceException {

    private final UUID leader;
    private final long feeCents;

    public InsufficientFundsForRealmCreationException(UUID leader, long feeCents) {
        super("Player " + leader + " cannot cover the " + feeCents + "-cent realm creation fee");
        this.leader = leader;
        this.feeCents = feeCents;
    }

    public UUID leader() {
        return leader;
    }

    public long feeCents() {
        return feeCents;
    }
}
