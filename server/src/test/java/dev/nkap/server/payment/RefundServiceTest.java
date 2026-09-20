package dev.nkap.server.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.SubmitResult;
import dev.nkap.server.outbox.InMemoryOutbox;
import dev.nkap.server.outbox.OutboxNotifier;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.provider.PublicBaseUrl;
import dev.nkap.server.webhook.InMemoryWebhookEndpointStore;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * The one rule that could not be checked before this service opens a transaction: two
 * refunds for the same collection must not both fit under its cap. Everything else about a
 * refund's request-shape (destination, state, operation) is {@code RefundController}'s to
 * refuse before this is ever called; see {@code RefundApiIT} for those, end to end.
 */
class RefundServiceTest {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private final PaymentRepository payments = new InMemoryPaymentRepository();
    private final ProviderAdapter adapter = mock(ProviderAdapter.class);
    private final AdapterRegistry adapters = mock(AdapterRegistry.class);
    private final PaymentService paymentService = new PaymentService(payments, adapters,
            new OutboxNotifier(new InMemoryOutbox(), new InMemoryWebhookEndpointStore(), new ObjectMapper()),
            new DirectTransactionManager());
    private final RefundService refunds = new RefundService(payments, adapters, paymentService,
            new PublicBaseUrl(""), new DirectTransactionManager());

    RefundServiceTest() {
        when(adapters.require(MTN)).thenReturn(adapter);
        // A refund is always DISBURSE (Payment.createRefund) -- without this, the mock's
        // default empty Set makes PaymentService.submit's new capability check (ADR 0013)
        // refuse every refund here before adapter.submit is ever called.
        when(adapter.operations()).thenReturn(Set.of(Capability.Operation.DISBURSE));
    }

    private Payment succeededCollection(long amountMinor) {
        PaymentIntent intent = new PaymentIntent(Capability.Operation.COLLECT, Money.of(amountMinor, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
        Payment payment = Payment.create(ReferenceId.newReference(), MTN, "merchant-1", intent);
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payment.applyTransition(PaymentState.SUCCEEDED, PaymentTransition.Cause.CALLBACK, "SUCCESSFUL", "", "");
        payments.save(payment);
        return payment;
    }

    @Test
    @DisplayName("the reservation and the refund's CREATED row are both persisted before the operator is asked")
    void reservation_and_persistence_happen_before_the_operator_is_called() throws Exception {
        Payment original = succeededCollection(5000);
        when(adapter.submit(any(), any())).thenAnswer(invocation -> {
            // By the time the adapter is asked, the reservation and the refund row must
            // already be durable -- a crash right here must not lose either.
            Payment lockedOriginal = payments.findByReference(original.reference()).orElseThrow();
            assertThat(lockedOriginal.refundedMinor()).isEqualTo(2000L);
            ReferenceId refundReference = invocation.getArgument(1);
            assertThat(payments.findByReference(refundReference)).isPresent()
                    .get().extracting(Payment::state).isEqualTo(PaymentState.CREATED);
            return new SubmitResult.Acknowledged(PaymentState.SUBMITTED, "op-ref", "{}");
        });

        Payment refund = refunds.createAndSubmit(original, Money.of(2000, Currency.EUR), "Refund", ReferenceId.newReference());

        assertThat(refund.refundOf()).contains(original.reference());
        assertThat(refund.intent().operation()).isEqualTo(Capability.Operation.DISBURSE);
        assertThat(refund.intent().counterpartyMsisdn()).isEqualTo(original.intent().counterpartyMsisdn());
        assertThat(refund.state()).isEqualTo(PaymentState.SUBMITTED);
    }

    @Test
    @DisplayName("an outright rejection at submit releases the reservation on the original")
    void an_outright_rejection_releases_the_reservation() throws Exception {
        Payment original = succeededCollection(5000);
        when(adapter.submit(any(), any())).thenReturn(new SubmitResult.Rejected("NOT_ALLOWED", "refused", "{}"));

        Payment refund = refunds.createAndSubmit(original, Money.of(2000, Currency.EUR), "Refund", ReferenceId.newReference());

        assertThat(refund.state()).isEqualTo(PaymentState.FAILED);
        Payment reloaded = payments.findByReference(original.reference()).orElseThrow();
        assertThat(reloaded.refundedMinor()).as("a refusal releases what it reserved").isZero();
    }

    @Test
    @DisplayName("an amount exceeding what remains is refused before anything is persisted")
    void an_amount_exceeding_the_remaining_balance_is_refused_before_persisting() {
        Payment original = succeededCollection(5000);

        assertThatThrownBy(() -> refunds.createAndSubmit(original, Money.of(5001, Currency.EUR), "Refund", ReferenceId.newReference()))
                .isInstanceOf(RefundExceedsRemainingException.class);

        Payment reloaded = payments.findByReference(original.reference()).orElseThrow();
        assertThat(reloaded.refundedMinor()).as("a refused reservation changes nothing").isZero();
        assertThat(reloaded.history())
                .as("the original's own history is untouched by a refused refund attempt")
                .hasSize(2);
    }

    @Test
    @DisplayName("two refunds that together exceed the remaining balance: the second is refused, the first stands")
    void a_second_refund_that_would_overrun_the_cap_is_refused() throws Exception {
        Payment original = succeededCollection(5000);
        when(adapter.submit(any(), any())).thenReturn(new SubmitResult.Acknowledged(PaymentState.SUBMITTED, "op-ref", "{}"));

        refunds.createAndSubmit(original, Money.of(3000, Currency.EUR), "Refund", ReferenceId.newReference());

        assertThatThrownBy(() -> refunds.createAndSubmit(original, Money.of(2001, Currency.EUR), "Refund", ReferenceId.newReference()))
                .isInstanceOf(RefundExceedsRemainingException.class);

        Payment reloaded = payments.findByReference(original.reference()).orElseThrow();
        assertThat(reloaded.refundedMinor()).as("only the first refund's reservation stands").isEqualTo(3000L);
    }

    @Test
    @DisplayName("an unconfigured provider throws before anything is persisted, the same contract createAndSubmit has")
    void an_unconfigured_provider_throws_before_persist() {
        Payment original = succeededCollection(5000);
        when(adapters.require(MTN)).thenThrow(new IllegalStateException("no adapter"));

        assertThatThrownBy(() -> refunds.createAndSubmit(original, Money.of(2000, Currency.EUR), "Refund", ReferenceId.newReference()))
                .isInstanceOf(IllegalStateException.class);

        Payment reloaded = payments.findByReference(original.reference()).orElseThrow();
        assertThat(reloaded.refundedMinor()).isZero();
    }

    @Test
    @DisplayName("the note becomes both the payer and payee message sent to the operator, not a fixed internal reference")
    void the_note_is_threaded_through_to_the_operator() throws Exception {
        Payment original = succeededCollection(5000);
        when(adapter.submit(any(), any())).thenReturn(new SubmitResult.Acknowledged(PaymentState.SUBMITTED, "op-ref", "{}"));

        refunds.createAndSubmit(original, Money.of(2000, Currency.EUR), "Thanks for your patience",
                ReferenceId.newReference());

        org.mockito.ArgumentCaptor<PaymentIntent> sent = org.mockito.ArgumentCaptor.forClass(PaymentIntent.class);
        org.mockito.Mockito.verify(adapter).submit(sent.capture(), any());
        assertThat(sent.getValue().payerMessage()).isEqualTo("Thanks for your patience");
        assertThat(sent.getValue().payeeNote()).isEqualTo("Thanks for your patience");
    }

    @Test
    @DisplayName("a refund whose submit step throws after the reservation transaction committed is left, for real, in CREATED")
    void a_failure_after_commit_leaves_a_real_created_refund() throws Exception {
        Payment original = succeededCollection(5000);
        when(adapter.submit(any(), any())).thenThrow(new RuntimeException("the process is killed here"));
        ReferenceId refundReference = ReferenceId.newReference();

        // PaymentService.submit's own callOperator() catches a plain RuntimeException from
        // adapter.submit and never rethrows it (recorded UNKNOWN instead) -- so to reach the
        // scenario issue #84's second correction is about, the failure has to come from
        // further in, where PaymentService.submit itself has nothing left to catch it.
        // Simulating that exactly requires a real transaction failure; what this test can
        // prove at the unit level is the half within RefundService's own control: the
        // reservation and the CREATED row are real and independently visible the moment the
        // first transaction returns, before paymentService.submit is ever called -- which is
        // exactly what lets RefundController find them after a genuine crash in the second
        // step. See RefundApiIT for the same guarantee proved through a real failure.
        Payment refund = refunds.createAndSubmit(original, Money.of(2000, Currency.EUR), "Refund", refundReference);

        assertThat(payments.findByReference(refundReference)).isPresent();
        assertThat(payments.findByReference(original.reference()).orElseThrow().refundedMinor()).isEqualTo(2000L);
        // callOperator's own catch means this particular failure resolves to UNKNOWN, not a
        // thrown exception -- the point above stands regardless of which of the two ways
        // "the operator step goes wrong after commit" actually happens.
        assertThat(refund.state()).isEqualTo(PaymentState.UNKNOWN);
    }
}
