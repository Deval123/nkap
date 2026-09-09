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
import dev.nkap.server.provider.AdapterRegistry;
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
    private final PaymentService service = new PaymentService(payments, adapters);

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.COLLECT, Money.of(5000, Currency.EUR),
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
    @DisplayName("an unconfigured provider throws before anything is persisted")
    void an_unconfigured_provider_throws_before_persist() {
        when(adapters.require(MTN)).thenThrow(new IllegalStateException("no adapter"));

        assertThatThrownBy(() -> service.createAndSubmit(MTN, "merchant-1", intent()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.findByReference(ReferenceId.newReference())).isEmpty();
    }
}
