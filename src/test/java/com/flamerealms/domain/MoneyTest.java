package com.flamerealms.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Money}.
 *
 * <p>{@link Money} itself does not reject a negative amount at construction
 * — by design, per its class Javadoc, a negative {@code Money} is a
 * meaningful value (a debt, a debit leg of a transfer, {@code deltaCents}
 * flowing out of a party). What actually rejects "non-positive" amounts is
 * every service entry point that accepts caller-supplied {@code Money} for a
 * deposit/withdraw ({@code EconomyServiceImpl}, {@code TreasuryServiceImpl})
 * — those are covered in {@code EconomyServiceImplTest}/{@code
 * TreasuryServiceImplTest}. This class only tests {@code Money}'s own,
 * narrower contract: it correctly reports the sign of whatever amount it is
 * given, and never throws for a negative one.
 */
final class MoneyTest {

    @Test
    void negativeAmountIsAllowedAndReportsNegative() {
        Money debt = Money.ofCents(-500L);

        assertThat(debt.isNegative()).isTrue();
        assertThat(debt.isPositive()).isFalse();
        assertThat(debt.cents()).isEqualTo(-500L);
    }

    @Test
    void zeroIsNeitherPositiveNorNegative() {
        assertThat(Money.ZERO.isPositive()).isFalse();
        assertThat(Money.ZERO.isNegative()).isFalse();
    }

    @Test
    void positiveAmountReportsPositive() {
        Money amount = Money.ofCents(150L);

        assertThat(amount.isPositive()).isTrue();
        assertThat(amount.isNegative()).isFalse();
    }

    @Test
    void addAndSubtractCombineCentsExactly() {
        Money a = Money.ofCents(300L);
        Money b = Money.ofCents(125L);

        assertThat(a.add(b)).isEqualTo(Money.ofCents(425L));
        assertThat(a.subtract(b)).isEqualTo(Money.ofCents(175L));
        // Subtracting past zero yields a negative Money rather than throwing.
        assertThat(b.subtract(a)).isEqualTo(Money.ofCents(-175L));
    }

    @Test
    void toStringFormatsNegativeAmountsWithALeadingSign() {
        assertThat(Money.ofCents(1234L).toString()).isEqualTo("$12.34");
        assertThat(Money.ofCents(-1234L).toString()).isEqualTo("-$12.34");
        assertThat(Money.ZERO.toString()).isEqualTo("$0.00");
    }

    @Test
    void compareToOrdersBySignedCents() {
        assertThat(Money.ofCents(100L)).isGreaterThan(Money.ofCents(50L));
        assertThat(Money.ofCents(-100L)).isLessThan(Money.ofCents(0L));
    }
}
