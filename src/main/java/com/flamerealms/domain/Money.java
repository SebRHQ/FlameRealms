package com.flamerealms.domain;

import java.util.Locale;

/**
 * An immutable amount of money, represented internally as a whole number of
 * cents.
 *
 * <p>This is the ONLY type any code in this project should use to represent
 * an amount of money, going forward. Never use {@code double}/{@code float}
 * for money — floating point cannot represent currency exactly and silently
 * accumulates rounding error. The single narrow exception is a future Vault
 * bridge, which will convert at its own boundary; that conversion is not
 * this class's concern and every other caller still sees only {@code Money}.
 *
 * @param cents the amount, in whole cents; may be negative (e.g. to
 *              represent a debt or a debit)
 */
public record Money(long cents) implements Comparable<Money> {

    /** The additive identity: zero cents. */
    public static final Money ZERO = new Money(0L);

    /**
     * Creates a {@link Money} from a whole number of cents.
     *
     * @param cents the amount, in whole cents
     * @return the corresponding {@link Money}
     */
    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    /**
     * Adds another amount to this one.
     *
     * @param other the amount to add
     * @return a new {@link Money} equal to {@code this + other}
     */
    public Money add(Money other) {
        return new Money(this.cents + other.cents);
    }

    /**
     * Subtracts another amount from this one.
     *
     * @param other the amount to subtract
     * @return a new {@link Money} equal to {@code this - other}
     */
    public Money subtract(Money other) {
        return new Money(this.cents - other.cents);
    }

    /**
     * @return {@code true} if this amount is strictly less than zero
     */
    public boolean isNegative() {
        return cents < 0L;
    }

    /**
     * @return {@code true} if this amount is strictly greater than zero
     */
    public boolean isPositive() {
        return cents > 0L;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.cents, other.cents);
    }

    /**
     * Formats this amount as a dollar string, e.g. {@code cents=1234} becomes
     * {@code "$12.34"}. Negative amounts render as {@code "-$12.34"}.
     *
     * @return the formatted dollar amount
     */
    @Override
    public String toString() {
        long absCents = Math.abs(cents);
        long dollars = absCents / 100L;
        long remainder = absCents % 100L;
        String sign = cents < 0L ? "-" : "";
        return String.format(Locale.ROOT, "%s$%d.%02d", sign, dollars, remainder);
    }
}
