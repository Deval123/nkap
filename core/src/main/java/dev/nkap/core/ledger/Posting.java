package dev.nkap.core.ledger;

import dev.nkap.core.money.Money;

import java.util.Objects;

/**
 * One line of a ledger entry: an amount posted to an account.
 *
 * <p>Sign carries the direction. A positive amount is a debit, a negative amount is a
 * credit. This is what lets the zero-sum invariant be a single addition rather than a
 * comparison of two hand-maintained columns.
 */
public record Posting(AccountId account, Money amount) {

    public Posting {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("Posting to " + account + " has a zero amount: it records nothing");
        }
    }

    public static Posting debit(AccountId account, Money amount) {
        requirePositive(amount);
        return new Posting(account, amount);
    }

    public static Posting credit(AccountId account, Money amount) {
        requirePositive(amount);
        return new Posting(account, amount.negate());
    }

    public boolean isDebit() {
        return amount.isPositive();
    }

    public boolean isCredit() {
        return amount.isNegative();
    }

    private static void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Use a positive amount and say which side it goes on: " + amount);
        }
    }

    @Override
    public String toString() {
        return account + " " + (isDebit() ? "DR " : "CR ") + Math.abs(amount.amount()) + " " + amount.currency();
    }
}
