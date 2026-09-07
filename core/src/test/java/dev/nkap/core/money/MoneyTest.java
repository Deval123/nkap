package dev.nkap.core.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    @DisplayName("the CFA franc has no minor unit, and the code knows it")
    void cfaFrancHasNoDecimals() {
        assertEquals(0, Currency.XAF.minorUnits());
        assertEquals(0, Currency.XOF.minorUnits());
        assertEquals(2, Currency.KES.minorUnits());
    }

    @Test
    @DisplayName("amounts in different currencies cannot be combined")
    void refusesImplicitConversion() {
        Money xaf = Money.of(5_000, Currency.XAF);
        Money kes = Money.of(5_000, Currency.KES);

        CurrencyMismatchException thrown = assertThrows(CurrencyMismatchException.class, () -> xaf.plus(kes));
        assertTrue(thrown.getMessage().contains("position account"), thrown.getMessage());
    }

    @Test
    @DisplayName("arithmetic overflow is an error, not a wrap-around")
    void overflowThrows() {
        Money huge = Money.of(Long.MAX_VALUE, Currency.XAF);
        assertThrows(ArithmeticException.class, () -> huge.plus(Money.of(1, Currency.XAF)));
    }

    @Test
    @DisplayName("signs describe direction")
    void signsDescribeDirection() {
        Money debit = Money.of(5_000, Currency.XAF);
        assertTrue(debit.isPositive());
        assertTrue(debit.negate().isNegative());
        assertTrue(debit.minus(debit).isZero());
    }
}
