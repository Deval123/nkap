package dev.nkap.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.webhook.InMemoryWebhookEndpointStore;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What is worth notifying, on its own: {@code SUCCEEDED}, {@code FAILED}, {@code EXPIRED},
 * and only for a merchant with a registered endpoint. Everything about the outbox write
 * itself commiting with the payment's state is {@code OutboxTransactionIT}'s job, against a
 * real transaction; this class is the decision in isolation.
 */
class OutboxNotifierTest {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final InMemoryWebhookEndpointStore endpoints = new InMemoryWebhookEndpointStore();
    private final OutboxNotifier notifier = new OutboxNotifier(outbox, endpoints, new ObjectMapper());

    private static Payment paymentIn(PaymentState state) {
        PaymentIntent intent = new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
        Payment payment = Payment.create(ReferenceId.newReference(), MTN, "merchant-1", intent);
        if (state != PaymentState.CREATED) {
            payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        }
        if (state != PaymentState.CREATED && state != PaymentState.SUBMITTED) {
            payment.applyTransition(state, PaymentTransition.Cause.QUERY, "CODE", "", "");
        }
        return payment;
    }

    @Test
    @DisplayName("a SUCCEEDED payment writes a payment.succeeded event, for a merchant with an endpoint")
    void succeeded_writes_an_event() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        Payment payment = paymentIn(PaymentState.SUCCEEDED);

        notifier.notifyIfTerminal(payment);

        assertThat(outbox.events()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("payment.succeeded");
            assertThat(event.merchantId()).isEqualTo("merchant-1");
            assertThat(event.payload()).contains(payment.reference().toString(), "\"state\":\"SUCCEEDED\"");
        });
    }

    @Test
    @DisplayName("a FAILED payment writes a payment.failed event")
    void failed_writes_an_event() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        Payment payment = paymentIn(PaymentState.FAILED);

        notifier.notifyIfTerminal(payment);

        assertThat(outbox.events()).singleElement()
                .satisfies(event -> assertThat(event.eventType()).isEqualTo("payment.failed"));
    }

    @Test
    @DisplayName("an EXPIRED payment writes a payment.expired event")
    void expired_writes_an_event() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        Payment payment = paymentIn(PaymentState.EXPIRED);

        notifier.notifyIfTerminal(payment);

        assertThat(outbox.events()).singleElement()
                .satisfies(event -> assertThat(event.eventType()).isEqualTo("payment.expired"));
    }

    @Test
    @DisplayName("a non-terminal state writes nothing, even for a merchant with an endpoint")
    void a_non_terminal_state_writes_nothing() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");

        notifier.notifyIfTerminal(paymentIn(PaymentState.SUBMITTED));
        notifier.notifyIfTerminal(paymentIn(PaymentState.PENDING));
        notifier.notifyIfTerminal(paymentIn(PaymentState.UNKNOWN));

        assertThat(outbox.events()).isEmpty();
    }

    @Test
    @DisplayName("a terminal state writes nothing for a merchant with no registered endpoint")
    void a_merchant_with_no_endpoint_gets_nothing() {
        Payment payment = paymentIn(PaymentState.SUCCEEDED);

        notifier.notifyIfTerminal(payment);

        assertThat(outbox.events()).isEmpty();
    }

    @Test
    @DisplayName("every event carries its own random id, not the payment's reference")
    void every_event_carries_its_own_id() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        Payment first = paymentIn(PaymentState.SUCCEEDED);
        Payment second = paymentIn(PaymentState.SUCCEEDED);

        notifier.notifyIfTerminal(first);
        notifier.notifyIfTerminal(second);

        assertThat(outbox.events()).extracting(OutboxEvent::id).doesNotHaveDuplicates();
        assertThat(outbox.events()).extracting(e -> e.id().toString())
                .noneMatch(id -> id.equals(first.reference().toString()) || id.equals(second.reference().toString()));
    }
}
