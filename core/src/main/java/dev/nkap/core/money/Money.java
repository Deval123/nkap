package dev.nkap.core.money;

import java.util.Objects;

/**
 * An amount of money, held as a signed integer count of minor units.
 *
 * <p>Never a floating-point number. 0.1 + 0.2 is not 0.3, and a payment system that
 * discovers this in production discovers it as a reconciliation break.
 *
 * <p>Amounts are signed: in a ledger posting, a positive amount is a debit and a
 * negative amount is a credit.
 */
public record Money(long amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amount, other.amount), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amount), currency);
    }

    public boolean isZero() {
        return amount == 0L;
    }

    public boolean isPositive() {
        return amount > 0L;
    }

    public boolean isNegative() {
        return amount < 0L;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amount, other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
