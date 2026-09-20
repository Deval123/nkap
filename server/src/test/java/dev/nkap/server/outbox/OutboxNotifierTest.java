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
            assertThat(event.payload()).contains(payment.reference().toString(), "\"state\":\"SUCCEEDED\"",
                    "\"cause\":\"QUERY\"");
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

    /**
     * Issue #177's own hazard: two independent lookups, one for {@code cause} and one for
     * {@code providerCode}, could each pick a different transition and describe a row that
     * never existed. This payment's two transitions carry deliberately distinct causes and
     * codes, so the two fields landing on the <em>same</em> row is only true if one helper
     * reads both from it -- a wrong pairing (this row's cause with the other row's code, or
     * the reverse) would mean the two were found independently.
     */
    @Test
    @DisplayName("cause and providerCode both come from the transition that made the payment terminal, not two independent lookups")
    void cause_and_provider_code_come_from_the_same_transition() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        PaymentIntent intent = new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
        Payment payment = Payment.create(ReferenceId.newReference(), MTN, "merchant-1", intent);
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "SUBMIT_CODE", "", "");
        payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.QUERY, "QUERY_CODE", "", "");

        notifier.notifyIfTerminal(payment);

        assertThat(outbox.events()).singleElement().satisfies(event -> {
            assertThat(event.payload()).contains("\"cause\":\"QUERY\"", "\"providerCode\":\"QUERY_CODE\"");
            assertThat(event.payload()).doesNotContain("SUBMIT_CODE");
        });
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

    // --- refunds get their own event type (issue #84) --------------------------------------

    private static Payment refundIn(PaymentState state) {
        ReferenceId original = ReferenceId.newReference();
        PaymentIntent intent = new PaymentIntent(Capability.Operation.DISBURSE, Money.of(2000, Currency.EUR),
                "46733123453", "refund", "refund of " + original, Map.of());
        Payment refund = Payment.createRefund(ReferenceId.newReference(), MTN, "merchant-1", intent, original);
        refund.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        if (state != PaymentState.SUBMITTED) {
            refund.applyTransition(state, PaymentTransition.Cause.QUERY, "CODE", "", "");
        }
        return refund;
    }

    @Test
    @DisplayName("a SUCCEEDED refund writes a refund.succeeded event, not payment.succeeded, carrying the original's reference")
    void a_succeeded_refund_writes_its_own_event_type() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        Payment refund = refundIn(PaymentState.SUCCEEDED);

        notifier.notifyIfTerminal(refund);

        assertThat(outbox.events()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("refund.succeeded");
            assertThat(event.payload()).contains(refund.refundOf().orElseThrow().toString());
        });
    }

    @Test
    @DisplayName("a FAILED refund writes a refund.failed event")
    void a_failed_refund_writes_its_own_event_type() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");

        notifier.notifyIfTerminal(refundIn(PaymentState.FAILED));

        assertThat(outbox.events()).singleElement()
                .satisfies(event -> assertThat(event.eventType()).isEqualTo("refund.failed"));
    }

    @Test
    @DisplayName("a plain disbursement's event carries an empty refundOf")
    void a_plain_disbursement_carries_no_refund_of() {
        endpoints.provision("merchant-1", "https://merchant.example/hooks");
        PaymentIntent intent = new PaymentIntent(Capability.Operation.DISBURSE, Money.of(5000, Currency.EUR),
                "46733123453", "payout", "payout", Map.of());
        Payment disbursement = Payment.create(ReferenceId.newReference(), MTN, "merchant-1", intent);
        disbursement.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        disbursement.applyTransition(PaymentState.SUCCEEDED, PaymentTransition.Cause.QUERY, "SUCCESSFUL", "", "");

        notifier.notifyIfTerminal(disbursement);

        assertThat(outbox.events()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("payment.succeeded");
            assertThat(event.payload()).contains("\"refundOf\":\"\"");
        });
    }
}
