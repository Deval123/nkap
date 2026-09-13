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
import dev.nkap.server.webhook.InMemoryWebhookEndpointStore;
import java.util.Map;
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
    private final RefundService refunds = new RefundService(payments, adapters, paymentService, new DirectTransactionManager());

    RefundServiceTest() {
        when(adapters.require(MTN)).thenReturn(adapter);
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

        Payment refund = refunds.createAndSubmit(original, Money.of(2000, Currency.EUR));

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

        Payment refund = refunds.createAndSubmit(original, Money.of(2000, Currency.EUR));

        assertThat(refund.state()).isEqualTo(PaymentState.FAILED);
        Payment reloaded = payments.findByReference(original.reference()).orElseThrow();
        assertThat(reloaded.refundedMinor()).as("a refusal releases what it reserved").isZero();
    }

    @Test
    @DisplayName("an amount exceeding what remains is refused before anything is persisted")
    void an_amount_exceeding_the_remaining_balance_is_refused_before_persisting() {
        Payment original = succeededCollection(5000);

        assertThatThrownBy(() -> refunds.createAndSubmit(original, Money.of(5001, Currency.EUR)))
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

        refunds.createAndSubmit(original, Money.of(3000, Currency.EUR));

        assertThatThrownBy(() -> refunds.createAndSubmit(original, Money.of(2001, Currency.EUR)))
                .isInstanceOf(RefundExceedsRemainingException.class);

        Payment reloaded = payments.findByReference(original.reference()).orElseThrow();
        assertThat(reloaded.refundedMinor()).as("only the first refund's reservation stands").isEqualTo(3000L);
    }

    @Test
    @DisplayName("an unconfigured provider throws before anything is persisted, the same contract createAndSubmit has")
    void an_unconfigured_provider_throws_before_persist() {
        Payment original = succeededCollection(5000);
        when(adapters.require(MTN)).thenThrow(new IllegalStateException("no adapter"));

        assertThatThrownBy(() -> refunds.createAndSubmit(original, Money.of(2000, Currency.EUR)))
                .isInstanceOf(IllegalStateException.class);

        Payment reloaded = payments.findByReference(original.reference()).orElseThrow();
        assertThat(reloaded.refundedMinor()).isZero();
    }
}
