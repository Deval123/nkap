package dev.nkap.core.ledger;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;

import java.util.List;

/**
 * An append-only record of balanced entries.
 *
 * <p>There is deliberately no update and no delete. A balance is never stored; it is the
 * sum of an account's postings, so it can always be explained by pointing at the entries
 * that produced it.
 */
public interface Ledger {

    /** Records an entry. Implementations must reject a duplicate id rather than overwrite. */
    void append(LedgerEntry entry);

    /** The balance of an account, computed from its postings. */
    Money balance(AccountId account, Currency currency);

    /** Every entry, oldest first. */
    List<LedgerEntry> entries();

    /** Every entry carrying the given business reference — one payment, typically. */
    List<LedgerEntry> entriesForReference(String reference);
}
