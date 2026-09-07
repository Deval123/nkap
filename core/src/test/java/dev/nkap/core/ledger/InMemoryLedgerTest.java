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

class InMemoryLedgerTest {

    private static final Instant AT = Instant.parse("2026-09-07T20:41:54Z");
    private static final AccountId FLOAT = AccountId.providerFloat("mtn", Currency.XAF);
    private static final AccountId PAYABLE = AccountId.merchantPayable("acme", Currency.XAF);
    private static final AccountId SUSPENSE = AccountId.suspense("mtn", Currency.XAF);

    private final Ledger ledger = new InMemoryLedger();

    @Test
    @DisplayName("a balance is the sum of an account's postings, never a stored number")
    void balanceIsComputed() {
        ledger.append(collection("e-1", 5_000));
        ledger.append(collection("e-2", 3_000));

        assertEquals(Money.of(8_000, Currency.XAF), ledger.balance(FLOAT, Currency.XAF));
        assertEquals(Money.of(-8_000, Currency.XAF), ledger.balance(PAYABLE, Currency.XAF));
    }

    @Test
    @DisplayName("an unknown account has a zero balance rather than an error")
    void unknownAccountIsZero() {
        assertEquals(Money.zero(Currency.XAF), ledger.balance(SUSPENSE, Currency.XAF));
    }

    @Test
    @DisplayName("the same entry cannot be recorded twice")
    void rejectsDuplicateEntryId() {
        ledger.append(collection("e-1", 5_000));

        LedgerInvariantViolation thrown =
                assertThrows(LedgerInvariantViolation.class, () -> ledger.append(collection("e-1", 5_000)));
        assertTrue(thrown.getMessage().contains("append-only"), thrown.getMessage());
        assertEquals(1, ledger.entries().size());
    }

    @Test
    @DisplayName("entries can be traced back to the payment that produced them")
    void findsEntriesByReference() {
        ledger.append(collection("e-1", 5_000));
        ledger.append(collection("e-2", 3_000));

        assertEquals(2, ledger.entriesForReference("pay-1").size());
        assertEquals(0, ledger.entriesForReference("pay-2").size());
    }

    @Test
    @DisplayName("the recorded history cannot be edited from outside")
    void historyIsImmutable() {
        ledger.append(collection("e-1", 5_000));
        assertThrows(UnsupportedOperationException.class, () -> ledger.entries().clear());
    }

    private static LedgerEntry collection(String id, long amount) {
        return new LedgerEntry(id, AT, "pay-1", "Collection", List.of(
                Posting.debit(FLOAT, Money.of(amount, Currency.XAF)),
                Posting.credit(PAYABLE, Money.of(amount, Currency.XAF))));
    }
}
