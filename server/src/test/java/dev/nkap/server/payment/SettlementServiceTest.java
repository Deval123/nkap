package dev.nkap.server.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.InMemoryLedger;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.server.support.LogCapture;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule these defend: a callback costs a query, never an entry, unless the operator
 * itself confirms the money moved.
 */
class SettlementServiceTest {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private final PaymentRepository payments = new InMemoryPaymentRepository();
    private final ProviderAdapter adapter = mock(ProviderAdapter.class);
    private final dev.nkap.server.provider.AdapterRegistry adapters = mock(dev.nkap.server.provider.AdapterRegistry.class);
    private final Ledger ledger = new InMemoryLedger();
    private final SettlementService settlement =
            new SettlementService(payments, adapters, ledger, new DirectTransactionManager());

    SettlementServiceTest() {
        when(adapters.require(MTN)).thenReturn(adapter);
    }

    private static PaymentIntent intent() {
        return intent(Capability.Operation.COLLECT);
    }

    private static PaymentIntent intent(Capability.Operation operation) {
        return new PaymentIntent(operation, Money.of(5000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
    }

    private Payment persisted(PaymentState state) {
        return persisted(state, Capability.Operation.COLLECT);
    }

    private Payment persisted(PaymentState state, Capability.Operation operation) {
        Payment payment = Payment.create(ReferenceId.newReference(), MTN, "merchant-1", intent(operation));
        if (state == PaymentState.SUBMITTED) {
            payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        } else if (state != PaymentState.CREATED) {
            throw new IllegalArgumentException("helper only makes CREATED or SUBMITTED payments");
        }
        payments.save(payment);
        return payment;
    }

    private static ProviderStatus status(PaymentState state, String code) {
        return new ProviderStatus(state, code, state == PaymentState.SUCCEEDED ? "txn-99" : "", null, "", "{\"status\":\"" + code + "\"}");
    }

    @Test
    @DisplayName("the payment's reference is a structured field on confirm's and settle's log lines, not just inside the sentence")
    void the_reference_is_a_structured_field_on_settlement_logs() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        try (LogCapture logs = new LogCapture(SettlementService.class)) {
            settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

            // settle() logs "settled ..." through the same logger, on the same thread as
            // confirm() — one MDC scope, opened once in confirm(), covers both.
            assertThat(logs.events()).isNotEmpty();
            assertThat(logs.events()).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                    .containsEntry("reference", payment.reference().toString()));
        }
    }

    @Test
    @DisplayName("a confirmed success moves the payment to SUCCEEDED and posts exactly the two postings of ADR 0006")
    void a_confirmed_success_settles_with_two_postings() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        ConfirmationOutcome outcome = settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(outcome.resolved()).as("a terminal state is a verdict — the reconciler leaves it alone").isTrue();
        assertThat(payment.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(payment.providerTransactionId()).isEqualTo("txn-99");

        assertThat(ledger.entriesForReference(payment.reference().toString())).singleElement().satisfies(entry -> {
            assertThat(entry.postings()).hasSize(2);
            assertThat(entry.currency()).isEqualTo(Currency.EUR);
            assertThat(entry.total()).isEqualTo(Money.of(5000, Currency.EUR));
            assertThat(signedAmount(entry, AccountId.providerFloat("mtn", Currency.EUR))).isEqualTo(5000L);
            assertThat(signedAmount(entry, AccountId.merchantPayable("merchant-1", Currency.EUR))).isEqualTo(-5000L);
        });
    }

    @Test
    @DisplayName("a settled disbursement posts the ADR 0007 mirror: float credited, merchant payable debited, summing to zero")
    void a_settled_disbursement_posts_the_adr_0007_mirror() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED, Capability.Operation.DISBURSE);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(ledger.entriesForReference(payment.reference().toString())).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("disbursement:" + payment.reference());
            assertThat(entry.postings()).hasSize(2);
            assertThat(entry.currency()).isEqualTo(Currency.EUR);
            assertThat(entry.total()).isEqualTo(Money.of(5000, Currency.EUR));
            // The mirror of a collection: the float goes DOWN (credit), the merchant is owed LESS (debit).
            assertThat(signedAmount(entry, AccountId.providerFloat("mtn", Currency.EUR))).isEqualTo(-5000L);
            assertThat(signedAmount(entry, AccountId.merchantPayable("merchant-1", Currency.EUR))).isEqualTo(5000L);
            long sum = entry.postings().stream().mapToLong(p -> p.amount().amount()).sum();
            assertThat(sum).as("the entry balances").isZero();
        });
    }

    @Test
    @DisplayName("the confirming query is asked under the payment's own operation, so the adapter never has to look it up")
    void the_query_carries_the_payments_own_capability() throws Exception {
        // Issue #67 / ADR 0008: query takes the capability because the caller already holds
        // it. This is the assertion that holds the lookup out of the adapter for good — if
        // it ever stops being the payment's own operation that travels, the MTN facade is
        // back to consulting gateway state to translate.
        Payment collection = persisted(PaymentState.SUBMITTED, Capability.Operation.COLLECT);
        Payment disbursement = persisted(PaymentState.SUBMITTED, Capability.Operation.DISBURSE);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        settlement.confirm(MTN, collection.reference(), PaymentTransition.Cause.CALLBACK);
        settlement.confirm(MTN, disbursement.reference(), PaymentTransition.Cause.CALLBACK);

        verify(adapter).query(collection.reference(), Capability.Operation.COLLECT);
        verify(adapter).query(disbursement.reference(), Capability.Operation.DISBURSE);
        // ...and never the other way round: a captured-any assertion would pass even if both
        // queries went out under one capability.
        verify(adapter, never()).query(collection.reference(), Capability.Operation.DISBURSE);
        verify(adapter, never()).query(disbursement.reference(), Capability.Operation.COLLECT);
    }

    @Test
    @DisplayName("a disbursement the operator refuses for insufficient funds is FAILED with the operator's code, and posts nothing")
    void a_refused_disbursement_is_failed_and_posts_nothing() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED, Capability.Operation.DISBURSE);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.FAILED, "NOT_ENOUGH_FUNDS"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.FAILED);
        assertThat(payment.history()).last().satisfies(t -> assertThat(t.operatorCode()).isEqualTo("NOT_ENOUGH_FUNDS"));
        assertThat(ledger.entries()).as("a failed disbursement writes nothing — no reserve, no prediction").isEmpty();
    }

    @Test
    @DisplayName("a duplicate callback produces one transition and one ledger entry, not two")
    void a_duplicate_callback_settles_once() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);
        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.history()).filteredOn(t -> t.cause() == PaymentTransition.Cause.CALLBACK).hasSize(1);
        assertThat(ledger.entriesForReference(payment.reference().toString())).hasSize(1);
    }

    @Test
    @DisplayName("a callback for a CREATED payment records CREATED -> SUBMITTED first, then the confirmed state")
    void a_callback_before_the_submit_response_records_both_legs() throws Exception {
        Payment payment = persisted(PaymentState.CREATED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(payment.history()).extracting(t -> t.from().name() + "->" + t.to().name())
                .containsExactly("CREATED->SUBMITTED", "SUBMITTED->SUCCEEDED");
        assertThat(payment.history()).allMatch(t -> t.cause() == PaymentTransition.Cause.CALLBACK);
        assertThat(ledger.entriesForReference(payment.reference().toString())).hasSize(1);
    }

    @Test
    @DisplayName("a callback whose confirming query does not answer changes nothing and writes no entry")
    void an_unanswered_confirming_query_changes_nothing() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenThrow(new ProviderUnavailableException("read timed out"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(payment.history()).filteredOn(t -> t.cause() == PaymentTransition.Cause.CALLBACK).isEmpty();
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    @DisplayName("a query that moves a payment to another non-terminal state is ADVANCED, not resolved — the reconciler keeps it")
    void a_non_terminal_transition_is_advanced_not_resolved() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.PENDING, "PENDING"));

        ConfirmationOutcome outcome = settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.PENDING);
        assertThat(outcome.kind()).isEqualTo(ConfirmationOutcome.Kind.ADVANCED);
        assertThat(outcome.resolved())
                .as("SUBMITTED -> PENDING is progress, not a verdict: still this gateway's to chase and to escalate")
                .isFalse();
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    @DisplayName("a callback whose query answers UNKNOWN leaves a SUBMITTED payment SUBMITTED")
    void an_inconclusive_query_does_not_overwrite_what_we_knew() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.UNKNOWN, "RESOURCE_NOT_FOUND"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    @DisplayName("a callback for an already-terminal payment does not even query, and changes nothing")
    void a_terminal_payment_is_left_alone() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(payment);

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.FAILED);
        verify(adapter, never()).query(any(), any());
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    @DisplayName("a confirmed failure moves the payment to FAILED and posts nothing")
    void a_confirmed_failure_posts_nothing() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.FAILED, "NOT_ENOUGH_FUNDS"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(payment.state()).isEqualTo(PaymentState.FAILED);
        assertThat(payment.history()).last().satisfies(t -> {
            assertThat(t.cause()).isEqualTo(PaymentTransition.Cause.CALLBACK);
            assertThat(t.operatorCode()).isEqualTo("NOT_ENOUGH_FUNDS");
        });
        assertThat(ledger.entries()).isEmpty();
    }

    private static long signedAmount(LedgerEntry entry, AccountId account) {
        return entry.postings().stream()
                .filter(p -> p.account().equals(account))
                .mapToLong(p -> p.amount().amount())
                .sum();
    }
}
