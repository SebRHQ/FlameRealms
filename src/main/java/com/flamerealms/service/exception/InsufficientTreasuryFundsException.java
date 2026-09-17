package com.flamerealms.service.exception;

/**
 * Thrown by {@code ClaimService#purchaseClaim} when a realm's treasury
 * cannot cover the price of the claim it is trying to buy.
 *
 * <p>Unlike {@code TreasuryService#withdraw}/{@code #deposit} — which return
 * a plain {@code false} for the same underlying "can't cover it" condition,
 * per their own contract — {@code purchaseClaim} returns a
 * {@code CompletableFuture<RealmClaim>}, and there is no meaningful
 * "successful but empty" claim to hand back. So this one genuine, expected
 * "can't afford it" outcome is surfaced as a specific exception instead of a
 * sentinel value, exactly the way every other rule violation on this
 * interface already is.
 */
public final class InsufficientTreasuryFundsException extends RealmServiceException {

    private final long realmId;
    private final long priceCents;

    public InsufficientTreasuryFundsException(long realmId, long priceCents) {
        super("Realm " + realmId + "'s treasury cannot cover the " + priceCents + "-cent claim price");
        this.realmId = realmId;
        this.priceCents = priceCents;
    }

    public long realmId() {
        return realmId;
    }

    public long priceCents() {
        return priceCents;
    }
}
