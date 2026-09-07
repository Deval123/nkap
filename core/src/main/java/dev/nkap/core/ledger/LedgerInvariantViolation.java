package dev.nkap.core.ledger;

/** Thrown when a proposed ledger entry would break double-entry bookkeeping. */
public class LedgerInvariantViolation extends IllegalArgumentException {

    public LedgerInvariantViolation(String message) {
        super(message);
    }
}
