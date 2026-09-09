package dev.nkap.core.ledger;

/**
 * Thrown when an entry whose id is already recorded is appended again.
 *
 * <p>A subclass of {@link LedgerInvariantViolation}, so a caller that catches the parent is
 * unaffected — but a <em>distinct</em> type, so a caller can tell "this exact entry is
 * already in the ledger" apart from every other reason an entry might be refused: an
 * unbalanced one, a currency mix, a foreign key.
 *
 * <p>The distinction is load-bearing at settlement. {@code InMemoryLedger} raises the parent
 * type only for a duplicate, so inferring "already recorded" from it happens to hold. A
 * database ledger raises the same parent type for constraints it enforces at the table — a
 * zero-sum violation, for instance — and mistaking one of those for "already recorded"
 * would leave a payment marked {@code SUCCEEDED} with nothing in the ledger. That is the
 * worst failure this system can have, and it would be silent. Catch this type; let
 * everything else propagate.
 */
public final class DuplicateLedgerEntryException extends LedgerInvariantViolation {

    private final String entryId;

    public DuplicateLedgerEntryException(String entryId) {
        super("Entry " + entryId + " is already recorded: the ledger is append-only, correct it with a reversal");
        this.entryId = entryId;
    }

    public String entryId() {
        return entryId;
    }
}
