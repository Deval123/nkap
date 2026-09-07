package dev.nkap.core.money;

/**
 * Thrown when two amounts in different currencies are combined.
 *
 * <p>Nkap never converts implicitly. Foreign exchange is a separate ledger entry
 * through a position account, with a rate someone can point at.
 */
public class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine " + left + " with " + right + ": convert explicitly through a position account");
    }
}
