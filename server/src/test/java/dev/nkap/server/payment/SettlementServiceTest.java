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
import dev.nkap.provider.QuerySubject;
import dev.nkap.server.outbox.InMemoryOutbox;
import dev.nkap.server.outbox.OutboxNotifier;
import dev.nkap.server.support.LogCapture;
import dev.nkap.server.webhook.InMemoryWebhookEndpointStore;
import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final InMemoryWebhookEndpointStore endpoints = new InMemoryWebhookEndpointStore();
    private final SettlementService settlement = new SettlementService(payments, adapters, ledger,
            new OutboxNotifier(outbox, endpoints, new ObjectMapper()), new DirectTransactionManager());

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

        verify(adapter).query(QuerySubject.of(collection.reference()), Capability.Operation.COLLECT);
        verify(adapter).query(QuerySubject.of(disbursement.reference()), Capability.Operation.DISBURSE);
        // ...and never the other way round: a captured-any assertion would pass even if both
        // queries went out under one capability.
        verify(adapter, never()).query(QuerySubject.of(collection.reference()), Capability.Operation.DISBURSE);
        verify(adapter, never()).query(QuerySubject.of(disbursement.reference()), Capability.Operation.COLLECT);
    }

    @Test
    @DisplayName("the confirming query carries whatever provider reference was recorded at submission (issue #96)")
    void the_query_carries_the_payments_own_provider_reference() throws Exception {
        // ADR 0008, amendment: query is handed the provider's own reference, exactly as
        // submit() recorded it on the payment -- not a blank one the adapter has to tolerate
        // and not one this method invents. MTN never records one (its 202 carries no body),
        // but an operator whose status call needs a token it issued would need this exact
        // value, unchanged, to be on the payment before this method ever runs.
        Payment payment = persisted(PaymentState.SUBMITTED);
        payment.recordProviderReference("op-ref-123");
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

        verify(adapter).query(new QuerySubject(payment.reference(), "op-ref-123"), Capability.Operation.COLLECT);
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

    // --- issue #129: a line for a transition, at most DEBUG for "changing nothing" --------

    @Test
    @DisplayName("a callback that settles a payment logs the transition at INFO, naming the cause and both states")
    void a_settling_callback_is_logged_at_info() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        try (LogCapture logs = new LogCapture(SettlementService.class)) {
            settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

            assertThat(logs.events())
                    .as("issue #129: a callback that settled a payment left no line before this")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getFormattedMessage())
                                .contains("CALLBACK").contains("SUBMITTED").contains("SUCCEEDED");
                    });
        }
    }

    @Test
    @DisplayName("a confirmation that changes nothing is DEBUG, not INFO -- the pair this settles against the resolving case above")
    void an_inconclusive_confirmation_is_debug_not_info() throws Exception {
        Payment payment = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.UNKNOWN, "INTERNAL_PROCESSING_ERROR"));

        try (LogCapture logs = new LogCapture(SettlementService.class)) {
            settlement.confirm(MTN, payment.reference(), PaymentTransition.Cause.CALLBACK);

            assertThat(logs.events())
                    .as("issue #129: per-attempt, per-payment, on a loop -- too loud at INFO")
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getLevel())
                            .as("no line this confirmation writes reaches INFO")
                            .isNotEqualTo(Level.INFO));
        }
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

    @Test
    @DisplayName("two installations settling the same currency credit different float accounts, and the same merchant payable")
    void different_installations_share_no_float_account() throws Exception {
        ProviderId cameroon = ProviderId.of("mtn-cm");
        ProviderId congo = ProviderId.of("mtn-cg");
        ProviderAdapter cameroonAdapter = mock(ProviderAdapter.class);
        ProviderAdapter congoAdapter = mock(ProviderAdapter.class);
        when(adapters.require(cameroon)).thenReturn(cameroonAdapter);
        when(adapters.require(congo)).thenReturn(congoAdapter);
        when(cameroonAdapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));
        when(congoAdapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        PaymentIntent xaf = new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.XAF),
                "46733123453", "rent", "march", Map.of());
        Payment fromCameroon = Payment.create(ReferenceId.newReference(), cameroon, "merchant-1", xaf);
        fromCameroon.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(fromCameroon);
        Payment fromCongo = Payment.create(ReferenceId.newReference(), congo, "merchant-1", xaf);
        fromCongo.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(fromCongo);

        settlement.confirm(cameroon, fromCameroon.reference(), PaymentTransition.Cause.CALLBACK);
        settlement.confirm(congo, fromCongo.reference(), PaymentTransition.Cause.CALLBACK);

        AccountId cameroonFloat = AccountId.providerFloat("mtn-cm", Currency.XAF);
        AccountId congoFloat = AccountId.providerFloat("mtn-cg", Currency.XAF);
        AccountId merchantPayable = AccountId.merchantPayable("merchant-1", Currency.XAF);
        assertThat(cameroonFloat).as("Cameroon and Congo both settle XAF, but are different installations").isNotEqualTo(congoFloat);

        assertThat(ledger.entriesForReference(fromCameroon.reference().toString())).singleElement().satisfies(entry -> {
            assertThat(signedAmount(entry, cameroonFloat)).isEqualTo(5000L);
            assertThat(signedAmount(entry, merchantPayable)).isEqualTo(-5000L);
        });
        assertThat(ledger.entriesForReference(fromCongo.reference().toString())).singleElement().satisfies(entry -> {
            assertThat(signedAmount(entry, congoFloat)).isEqualTo(5000L);
            // Same account both times -- the merchant is owed one XAF sum, not one per installation.
            assertThat(signedAmount(entry, merchantPayable)).isEqualTo(-5000L);
        });
    }

    // --- refunds (issue #84) --------------------------------------------------------------

    @Test
    @DisplayName("a settled refund posts its own refund:<ref> entry mirroring the collection, "
            + "and the original collection's entry is still present and unmodified")
    void a_settled_refund_posts_its_own_entry_and_leaves_the_original_alone() throws Exception {
        Payment original = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));
        settlement.confirm(MTN, original.reference(), PaymentTransition.Cause.CALLBACK);
        LedgerEntry collectionEntry = ledger.entriesForReference(original.reference().toString()).get(0);

        Payment refund = refundOf(original, Money.of(2000, Currency.EUR), PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));

        settlement.confirm(MTN, refund.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(refund.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(ledger.entriesForReference(refund.reference().toString())).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("refund:" + refund.reference());
            assertThat(entry.description()).contains(original.reference().toString());
            // The mirror of the collection: the float goes down, the merchant is owed less --
            // the same shape settle() already writes for any disbursement.
            assertThat(signedAmount(entry, AccountId.providerFloat("mtn", Currency.EUR))).isEqualTo(-2000L);
            assertThat(signedAmount(entry, AccountId.merchantPayable("merchant-1", Currency.EUR))).isEqualTo(2000L);
        });
        assertThat(ledger.entriesForReference(original.reference().toString()))
                .as("the original entry is still present and unmodified -- the point of the whole design")
                .containsExactly(collectionEntry);
        assertThat(ledger.entries()).as("nothing here is a reversal").noneMatch(e -> e.description().contains("Reversal"));
    }

    @Test
    @DisplayName("a refund the operator refuses releases the amount it reserved on the original")
    void a_refused_refund_releases_its_reservation() throws Exception {
        Payment original = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));
        settlement.confirm(MTN, original.reference(), PaymentTransition.Cause.CALLBACK);

        Payment refund = refundOf(original, Money.of(2000, Currency.EUR), PaymentState.SUBMITTED);
        assertThat(original.refundedMinor()).isEqualTo(2000L);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.FAILED, "NOT_ALLOWED"));

        settlement.confirm(MTN, refund.reference(), PaymentTransition.Cause.CALLBACK);

        assertThat(refund.state()).isEqualTo(PaymentState.FAILED);
        assertThat(original.refundedMinor()).as("FAILED releases what the refund reserved").isZero();
        assertThat(ledger.entriesForReference(refund.reference().toString())).isEmpty();
    }

    @Test
    @DisplayName("a refund the payer never approves in time expires and releases its reservation")
    void an_expired_refund_releases_its_reservation() throws Exception {
        Payment original = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));
        settlement.confirm(MTN, original.reference(), PaymentTransition.Cause.CALLBACK);

        Payment refund = refundOf(original, Money.of(2000, Currency.EUR), PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.EXPIRED, "EXPIRED"));

        settlement.confirm(MTN, refund.reference(), PaymentTransition.Cause.RECONCILER);

        assertThat(refund.state()).isEqualTo(PaymentState.EXPIRED);
        assertThat(original.refundedMinor()).as("EXPIRED releases what the refund reserved").isZero();
    }

    @Test
    @DisplayName("a refund still in flight (UNKNOWN) keeps its reservation")
    void an_unresolved_refund_keeps_its_reservation() throws Exception {
        Payment original = persisted(PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenReturn(status(PaymentState.SUCCEEDED, "SUCCESSFUL"));
        settlement.confirm(MTN, original.reference(), PaymentTransition.Cause.CALLBACK);

        Payment refund = refundOf(original, Money.of(2000, Currency.EUR), PaymentState.SUBMITTED);
        when(adapter.query(any(), any())).thenThrow(new ProviderUnavailableException("read timed out"));

        settlement.confirm(MTN, refund.reference(), PaymentTransition.Cause.RECONCILER);

        assertThat(refund.state()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(original.refundedMinor())
                .as("no answer changes nothing, including the reservation")
                .isEqualTo(2000L);
    }

    /** A refund of {@code original}, reserved and persisted the way {@code RefundService} does it. */
    private Payment refundOf(Payment original, Money amount, PaymentState state) {
        original.reserveRefund(amount);
        payments.save(original);
        PaymentIntent refundIntent = new PaymentIntent(Capability.Operation.DISBURSE, amount,
                original.intent().counterpartyMsisdn(), "refund", "refund of " + original.reference(), Map.of());
        Payment refund = Payment.createRefund(ReferenceId.newReference(), MTN, "merchant-1", refundIntent, original.reference());
        if (state == PaymentState.SUBMITTED) {
            refund.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        } else if (state != PaymentState.CREATED) {
            throw new IllegalArgumentException("helper only makes CREATED or SUBMITTED refunds");
        }
        payments.save(refund);
        return refund;
    }

    private static long signedAmount(LedgerEntry entry, AccountId account) {
        return entry.postings().stream()
                .filter(p -> p.account().equals(account))
                .mapToLong(p -> p.amount().amount())
                .sum();
    }
}
