package dev.nkap.core.ledger;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first test of this project, and the assertion the rest of it is built behind:
 * an entry whose postings do not sum to zero cannot exist.
 */
class LedgerEntryTest {

    private static final Instant AT = Instant.parse("2026-09-07T20:41:54Z");

    private static Money xaf(long amount) {
        return Money.of(amount, Currency.XAF);
    }

    @Test
    @DisplayName("an entry whose postings do not sum to zero is rejected")
    void rejectsUnbalancedEntry() {
        LedgerInvariantViolation thrown = assertThrows(LedgerInvariantViolation.class, () ->
                new LedgerEntry("e-1", AT, "pay-1", "Collection", List.of(
                        Posting.debit(AccountId.providerFloat("mtn", Currency.XAF), xaf(5_000)),
                        Posting.credit(AccountId.merchantPayable("acme", Currency.XAF), xaf(4_900)))));

        assertTrue(thrown.getMessage().contains("does not balance"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("100"), thrown.getMessage());
    }

    @Test
    @DisplayName("a collection splits into float, payable and fees, and balances")
    void acceptsBalancedCollection() {
        LedgerEntry entry = new LedgerEntry("e-2", AT, "pay-1", "Collection of 5000 XAF for acme", List.of(
                Posting.debit(AccountId.providerFloat("mtn", Currency.XAF), xaf(5_000)),
                Posting.credit(AccountId.merchantPayable("acme", Currency.XAF), xaf(4_850)),
                Posting.credit(AccountId.fees("mtn", Currency.XAF), xaf(100)),
                Posting.credit(AccountId.fees("platform", Currency.XAF), xaf(50))));

        assertEquals(4, entry.postings().size());
        assertEquals(xaf(5_000), entry.total());
        assertEquals(Currency.XAF, entry.currency());
    }

    @Test
    @DisplayName("a single posting is not a movement")
    void rejectsSinglePosting() {
        assertThrows(LedgerInvariantViolation.class, () ->
                new LedgerEntry("e-3", AT, "pay-1", "Half a movement", List.of(
                        Posting.debit(AccountId.providerFloat("mtn", Currency.XAF), xaf(5_000)))));
    }

    @Test
    @DisplayName("an entry may not mix currencies")
    void rejectsMixedCurrencies() {
        LedgerInvariantViolation thrown = assertThrows(LedgerInvariantViolation.class, () ->
                new LedgerEntry("e-4", AT, "pay-1", "Implicit FX", List.of(
                        Posting.debit(AccountId.providerFloat("mtn", Currency.XAF), xaf(5_000)),
                        Posting.credit(AccountId.merchantPayable("acme", Currency.KES),
                                Money.of(5_000, Currency.KES)))));

        assertTrue(thrown.getMessage().contains("position account"), thrown.getMessage());
    }

    @Test
    @DisplayName("postings cannot be changed after the entry is built")
    void postingsAreImmutable() {
        LedgerEntry entry = balancedEntry("e-5");
        assertThrows(UnsupportedOperationException.class, () -> entry.postings().clear());
    }

    @Test
    @DisplayName("a reversal flips every sign and nets the original to zero")
    void reversalNetsToZero() {
        LedgerEntry original = balancedEntry("e-6");
        LedgerEntry reversal = LedgerEntry.reversalOf(original, "e-7", AT, "duplicate callback");

        Ledger ledger = new InMemoryLedger();
        ledger.append(original);
        ledger.append(reversal);

        assertEquals(Money.zero(Currency.XAF),
                ledger.balance(AccountId.providerFloat("mtn", Currency.XAF), Currency.XAF));
        assertEquals(Money.zero(Currency.XAF),
                ledger.balance(AccountId.merchantPayable("acme", Currency.XAF), Currency.XAF));
        assertTrue(reversal.description().contains("duplicate callback"));
    }

    private static LedgerEntry balancedEntry(String id) {
        return new LedgerEntry(id, AT, "pay-1", "Collection", List.of(
                Posting.debit(AccountId.providerFloat("mtn", Currency.XAF), xaf(5_000)),
                Posting.credit(AccountId.merchantPayable("acme", Currency.XAF), xaf(5_000))));
    }
}
