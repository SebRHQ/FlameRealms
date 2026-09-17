package com.flamerealms.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Parses a player-typed decimal dollar string (e.g. {@code "500"} or
 * {@code "12.50"}) into whole cents.
 *
 * <p>This is an exact port of the algorithm that used to live as a private
 * method on {@code com.flamerealms.command.realm.RealmCommand}
 * ({@code parseAmountToCents}) — same {@link BigDecimal} parsing, same
 * rejection rules, same {@code messages.yml} key strings, not redesigned.
 * It's pulled out here so both the command line and this stage's {@link
 * com.flamerealms.gui.AmountMenu} can share one implementation; a later
 * stage is expected to delete {@code RealmCommand}'s own private copy and
 * switch it to call this one instead — this stage does not touch
 * {@code RealmCommand} itself.
 */
public final class MoneyParsing {

    private static final BigDecimal CENTS_PER_UNIT = BigDecimal.valueOf(100);

    private MoneyParsing() {
    }

    /**
     * @param raw a decimal dollar string, e.g. {@code "500"} or {@code "12.50"}
     * @return the equivalent whole number of cents
     * @throws InvalidAmountException describing exactly what was wrong with {@code raw}:
     *                                 unparseable ({@code "amount-invalid"}), more than 2
     *                                 decimal places ({@code "amount-too-many-decimals"}),
     *                                 non-positive ({@code "amount-not-positive"}), or too
     *                                 large to fit a {@code long} number of cents
     *                                 ({@code "amount-too-large"})
     */
    public static long parseAmountToCents(String raw) throws InvalidAmountException {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new InvalidAmountException("amount-invalid");
        }

        if (parsed.stripTrailingZeros().scale() > 2) {
            throw new InvalidAmountException("amount-too-many-decimals");
        }
        if (parsed.signum() <= 0) {
            throw new InvalidAmountException("amount-not-positive");
        }

        try {
            // scale() <= 2 was just verified, so this multiplication is
            // always an exact whole number of cents — RoundingMode.UNNECESSARY
            // documents that no actual rounding ever happens here.
            return parsed.multiply(CENTS_PER_UNIT).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidAmountException("amount-too-large");
        }
    }
}
