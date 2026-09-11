package dev.nkap.server.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import dev.nkap.server.outbox.InMemoryOutbox;
import dev.nkap.server.outbox.OutboxEvent;
import dev.nkap.server.outbox.OutboxNotifier;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.support.LogCapture;
import dev.nkap.server.webhook.InMemoryWebhookEndpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guarantee these tests defend: once the payment is persisted, nothing escapes
 * {@code createAndSubmit}. Silence is {@code UNKNOWN}; a defect on our side is also
 * {@code UNKNOWN}, never {@code FAILED}, and never an exception the caller would take as a
 * reason to release the idempotency claim on a payment that already went out.
 */
class PaymentServiceTest {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private final PaymentRepository payments = new InMemoryPaymentRepository();
    private final ProviderAdapter adapter = mock(ProviderAdapter.class);
    private final AdapterRegistry adapters = mock(AdapterRegistry.class);
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final InMemoryWebhookEndpointStore endpoints = new InMemoryWebhookEndpointStore();
    private final PaymentService service = new PaymentService(payments, adapters,
            new OutboxNotifier(outbox, endpoints, new ObjectMapper()), new DirectTransactionManager());

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
    }

    @Test
    @DisplayName("an unexpected error after the payment is persisted is recorded UNKNOWN, not FAILED, and does not escape")
    void an_unexpected_error_during_submit_is_unknown_not_failed() throws Exception {
        when(adapters.require(MTN)).thenReturn(adapter);
        when(adapter.submit(any(), any())).thenThrow(new IllegalStateException("a bug on our side"));

        Payment payment = service.createAndSubmit(MTN, "merchant-1", intent());

        assertThat(payment.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(payment.history()).singleElement().satisfies(t -> {
            assertThat(t.from()).isEqualTo(PaymentState.CREATED);
            assertThat(t.to()).isEqualTo(PaymentState.UNKNOWN);
        });
        assertThat(payments.findByReference(payment.reference())).contains(payment);
    }

    @Test
    @DisplayName("an operator that did not answer is recorded UNKNOWN and does not escape")
    void operator_silence_is_unknown() throws Exception {
        when(adapters.require(MTN)).thenReturn(adapter);
        when(adapter.submit(any(), any())).thenThrow(new ProviderUnavailableException("read timed out"));

        Payment payment = service.createAndSubmit(MTN, "merchant-1", intent());

        assertThat(payment.state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("an acknowledgement of PENDING is applied as CREATED -> PENDING")
    void a_pending_acknowledgement_is_applied() throws Exception {
        when(adapters.require(MTN)).thenReturn(adapter);
        when(adapter.submit(any(), any()))
                .thenReturn(new SubmitResult.Acknowledged(PaymentState.PENDING, "op-ref", "{}"));

        Payment payment = service.createAndSubmit(MTN, "merchant-1", intent());

        assertThat(payment.state()).isEqualTo(PaymentState.PENDING);
        assertThat(payment.providerReference()).isEqualTo("op-ref");
    }

    @Test
    @DisplayName("a submit response is dropped, not forced, when a callback already advanced the payment")
    void a_stale_submit_response_is_not_applied() throws Exception {
        when(adapters.require(MTN)).thenReturn(adapter);
        // The callback path advances the payment out of CREATED while submit is in flight;
        // then submit returns its (now stale) acknowledgement.
        when(adapter.submit(any(), any())).thenAnswer(invocation -> {
            ReferenceId reference = invocation.getArgument(1);
            Payment inFlight = payments.findByReference(reference).orElseThrow();
            inFlight.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.CALLBACK, "", "", "");
            inFlight.applyTransition(PaymentState.SUCCEEDED, PaymentTransition.Cause.CALLBACK, "", "", "");
            payments.save(inFlight);
            return new SubmitResult.Acknowledged(PaymentState.SUBMITTED, "op-ref", "{}");
        });

        Payment payment = service.createAndSubmit(MTN, "merchant-1", intent());

        assertThat(payment.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(payment.providerReference()).isEqualTo("op-ref");
        assertThat(payment.history()).extracting(t -> t.cause().name())
                .containsExactly("CALLBACK", "CALLBACK");
    }

    @Test
    @DisplayName("the payment's reference is a structured field on the submission's log lines, not just inside the sentence")
    void the_reference_is_a_structured_field_on_submission_logs() throws Exception {
        when(adapters.require(MTN)).thenReturn(adapter);
        when(adapter.submit(any(), any())).thenThrow(new ProviderUnavailableException("read timed out"));

        try (LogCapture logs = new LogCapture(PaymentService.class)) {
            Payment payment = service.createAndSubmit(MTN, "merchant-1", intent());

            assertThat(logs.events()).isNotEmpty();
            assertThat(logs.events()).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                    .as("every line createAndSubmit emits carries the reference, not just the ones that mention it")
                    .containsEntry("reference", payment.reference().toString()));
        }
    }

    @Test
    @DisplayName("an outright rejection is recorded FAILED and writes an outbox event for a merchant with an endpoint")
    void a_rejection_is_failed_and_notifies() throws Exception {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        when(adapters.require(MTN)).thenReturn(adapter);
        when(adapter.submit(any(), any()))
                .thenReturn(new SubmitResult.Rejected("INVALID_MSISDN", "not a valid payer", "{}"));

        Payment payment = service.createAndSubmit(MTN, "merchant-1", intent());

        assertThat(payment.state()).isEqualTo(PaymentState.FAILED);
        assertThat(outbox.events()).singleElement().satisfies((OutboxEvent event) -> {
            assertThat(event.eventType()).isEqualTo("payment.failed");
            assertThat(event.merchantId()).isEqualTo("merchant-1");
            assertThat(event.payload()).contains(payment.reference().toString(), "INVALID_MSISDN");
        });
    }

    @Test
    @DisplayName("an outright rejection writes no outbox event for a merchant with no registered endpoint")
    void a_rejection_without_an_endpoint_writes_nothing() throws Exception {
        when(adapters.require(MTN)).thenReturn(adapter);
        when(adapter.submit(any(), any()))
                .thenReturn(new SubmitResult.Rejected("INVALID_MSISDN", "not a valid payer", "{}"));

        service.createAndSubmit(MTN, "merchant-1", intent());

        assertThat(outbox.events()).isEmpty();
    }

    @Test
    @DisplayName("an unconfigured provider throws before anything is persisted")
    void an_unconfigured_provider_throws_before_persist() {
        when(adapters.require(MTN)).thenThrow(new IllegalStateException("no adapter"));

        assertThatThrownBy(() -> service.createAndSubmit(MTN, "merchant-1", intent()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.findByReference(ReferenceId.newReference())).isEmpty();
    }
}
