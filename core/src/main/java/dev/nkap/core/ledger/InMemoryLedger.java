package dev.nkap.core.ledger;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A ledger held in memory, for tests and for the reference implementation of the rules.
 *
 * <p>The production ledger lives in PostgreSQL with the same invariants expressed as
 * table constraints, because a rule enforced only in Java is a rule that a migration
 * script can walk straight through.
 */
public final class InMemoryLedger implements Ledger {

    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Set<String> knownIds = new HashSet<>();

    @Override
    public synchronized void append(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!knownIds.add(entry.id())) {
            throw new LedgerInvariantViolation(
                    "Entry " + entry.id() + " is already recorded: the ledger is append-only, correct it with a reversal");
        }
        entries.add(entry);
    }

    @Override
    public synchronized Money balance(AccountId account, Currency currency) {
        Objects.requireNonNull(account, "account");
        long sum = 0L;
        for (LedgerEntry entry : entries) {
            for (Posting posting : entry.postings()) {
                if (posting.account().equals(account) && posting.amount().currency() == currency) {
                    sum = Math.addExact(sum, posting.amount().amount());
                }
            }
        }
        return Money.of(sum, currency);
    }

    @Override
    public synchronized List<LedgerEntry> entries() {
        return List.copyOf(entries);
    }

    @Override
    public synchronized List<LedgerEntry> entriesForReference(String reference) {
        Objects.requireNonNull(reference, "reference");
        List<LedgerEntry> matching = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.reference().equals(reference)) {
                matching.add(entry);
            }
        }
        return List.copyOf(matching);
    }
}
