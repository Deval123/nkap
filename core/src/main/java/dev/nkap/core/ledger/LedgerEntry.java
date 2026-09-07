package dev.nkap.core.ledger;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, balanced set of postings recorded at one moment.
 *
 * <p>Three rules are enforced here and nowhere else, so that no code path can write an
 * unbalanced entry:
 *
 * <ol>
 *   <li>at least two postings — a movement always has two sides;</li>
 *   <li>one currency across the entry — cross-currency movements are two entries and a
 *       position account;</li>
 *   <li>the signed amounts sum to exactly zero.</li>
 * </ol>
 *
 * <p>Entries are never modified or deleted. A mistake is corrected by
 * {@link #reversalOf} — the original stays in the record, which is the point.
 */
public record LedgerEntry(
        String id,
        Instant occurredAt,
        String reference,
        String description,
        List<Posting> postings) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(postings, "postings");

        postings = List.copyOf(postings);

        if (postings.size() < 2) {
            throw new LedgerInvariantViolation(
                    "Entry " + id + " has " + postings.size() + " posting(s): a movement always has at least two sides");
        }

        Currency currency = postings.get(0).amount().currency();
        for (Posting posting : postings) {
            if (posting.amount().currency() != currency) {
                throw new LedgerInvariantViolation(
                        "Entry " + id + " mixes " + currency + " and " + posting.amount().currency()
                                + ": cross-currency movements are two entries through a position account");
            }
        }

        long sum = 0L;
        for (Posting posting : postings) {
            sum = Math.addExact(sum, posting.amount().amount());
        }
        if (sum != 0L) {
            throw new LedgerInvariantViolation(
                    "Entry " + id + " does not balance: postings sum to " + sum + " " + currency + ", expected 0");
        }
    }

    public Currency currency() {
        return postings.get(0).amount().currency();
    }

    /** The total debited, which by construction equals the total credited. */
    public Money total() {
        long debits = 0L;
        for (Posting posting : postings) {
            if (posting.isDebit()) {
                debits = Math.addExact(debits, posting.amount().amount());
            }
        }
        return Money.of(debits, currency());
    }

    /**
     * The correcting entry for a mistake: the same postings with every sign flipped.
     *
     * <p>This is how a ledger is fixed. Rewriting or deleting the original would destroy
     * the only evidence of what was believed at the time.
     */
    public static LedgerEntry reversalOf(LedgerEntry original, String id, Instant at, String reason) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(reason, "reason");
        List<Posting> reversed = new ArrayList<>(original.postings().size());
        for (Posting posting : original.postings()) {
            reversed.add(new Posting(posting.account(), posting.amount().negate()));
        }
        return new LedgerEntry(id, at, original.reference(), "Reversal of " + original.id() + ": " + reason, reversed);
    }
}
