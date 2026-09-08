package dev.nkap.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProviderStatusTest {

    @Test
    @DisplayName("a settled payment exposes its provider transaction id")
    void a_settled_payment_exposes_its_transaction_id() {
        ProviderStatus settled = new ProviderStatus(
                PaymentState.SUCCEEDED, "SUCCESSFUL", "1510430965", null, "", "{...}");

        assertEquals(Optional.of("1510430965"), settled.transactionId());
    }

    @Test
    @DisplayName("a pending payment exposes no transaction id")
    void a_pending_payment_exposes_none() {
        ProviderStatus pending = new ProviderStatus(
                PaymentState.PENDING, "PENDING", null, null, "", "{...}");

        assertTrue(pending.transactionId().isEmpty());
        assertEquals("", pending.providerTransactionId());
    }

    @Test
    @DisplayName("a blank transaction id is exposed as none, not as an empty string")
    void a_blank_transaction_id_is_none() {
        ProviderStatus status = new ProviderStatus(
                PaymentState.PENDING, "PENDING", "   ", null, "", "{}");

        assertTrue(status.transactionId().isEmpty());
    }

    @Test
    @DisplayName("unknown() carries no transaction id and no fee")
    void unknown_carries_no_transaction_id_and_no_fee() {
        ProviderStatus unknown = ProviderStatus.unknown("RESOURCE_NOT_FOUND", "{\"code\":\"RESOURCE_NOT_FOUND\"}");

        assertEquals(PaymentState.UNKNOWN, unknown.state());
        assertTrue(unknown.transactionId().isEmpty());
        assertTrue(unknown.fee().isEmpty());
        assertEquals("RESOURCE_NOT_FOUND", unknown.providerStatusCode());
    }

    @Test
    @DisplayName("optional string fields normalise null to empty; fee stays null and is exposed as an empty Optional")
    void optional_fields_normalise() {
        ProviderStatus status = new ProviderStatus(PaymentState.FAILED, null, null, null, null, null);

        assertEquals("", status.providerStatusCode());
        assertEquals("", status.providerTransactionId());
        assertEquals("", status.failureReason());
        assertEquals("", status.rawResponse());
        assertFalse(status.fee().isPresent());
    }

    @Test
    @DisplayName("a fee, when present, round-trips through fee()")
    void a_present_fee_round_trips() {
        Money fee = Money.of(25, Currency.EUR);
        ProviderStatus status = new ProviderStatus(PaymentState.SUCCEEDED, "SUCCESSFUL", "tx", fee, "", "{}");

        assertEquals(Optional.of(fee), status.fee());
    }
}
