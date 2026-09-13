package dev.nkap.server.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.webhook.WebhookEndpoint;
import dev.nkap.server.webhook.WebhookEndpointStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place that decides what is worth notifying, and builds the event for it. Both
 * write paths that can move a payment to a terminal state — {@code SettlementService}
 * (a query the operator answered) and {@code PaymentService} (an outright rejection at
 * submit) — call {@link #notifyIfTerminal} from inside their own open transaction, so the
 * event lands in the outbox with the state change or not at all (ADR 0003).
 *
 * <p><strong>What is worth notifying</strong>: {@code SUCCEEDED}, {@code FAILED},
 * {@code EXPIRED} — the verdict a merchant is waiting for. An escalation is deliberately
 * not here: it means the reconciler is stuck, not the merchant, and repeating "we do not
 * know" is noise a merchant cannot act on (see {@code Reconciler}'s escalation, which pages
 * a human instead).
 *
 * <p>No event is written for a merchant with no registered endpoint — there is nothing to
 * notify. {@link WebhookEndpointStore#find} runs inside the same transaction as everything
 * else here, so this is a plain read, not a network call: it cannot be the "operator did not
 * answer" kind of slowness the settlement and submit paths are already careful to keep
 * outside their transactions.
 */
@Component
public final class OutboxNotifier {

    private final Outbox outbox;
    private final WebhookEndpointStore endpoints;
    private final ObjectMapper json;

    public OutboxNotifier(Outbox outbox, WebhookEndpointStore endpoints, ObjectMapper json) {
        this.outbox = outbox;
        this.endpoints = endpoints;
        this.json = json;
    }

    public void notifyIfTerminal(Payment payment) {
        String type = eventType(payment.state());
        if (type == null) {
            return;
        }
        Optional<WebhookEndpoint> endpoint = endpoints.find(payment.merchantId());
        if (endpoint.isEmpty()) {
            return;
        }
        outbox.append(buildEvent(type, payment));
    }

    private OutboxEvent buildEvent(String type, Payment payment) {
        UUID id = UUID.randomUUID();
        Money amount = payment.intent().amount();
        PaymentEventPayload data = new PaymentEventPayload(
                id.toString(),
                type,
                payment.reference().toString(),
                payment.provider().toString(),
                payment.intent().operation().name(),
                amount.amount(),
                amount.currency().name(),
                payment.state().name(),
                lastOperatorCode(payment.history()),
                payment.providerTransactionId(),
                Instant.now().toString());
        String body;
        try {
            body = json.writeValueAsString(data);
        } catch (JsonProcessingException impossible) {
            // PaymentEventPayload is a record of primitives and strings; there is no field
            // Jackson can fail to serialise. If this ever throws, the payload shape changed
            // and this is a bug to fix, not a delivery to skip.
            throw new IllegalStateException("failed to serialise a payment event payload", impossible);
        }
        return new OutboxEvent(id, payment.merchantId(), type, body);
    }

    /** {@code null} for every state that is not worth telling a merchant about. */
    private static String eventType(PaymentState state) {
        return switch (state) {
            case SUCCEEDED -> "payment.succeeded";
            case FAILED -> "payment.failed";
            case EXPIRED -> "payment.expired";
            default -> null;
        };
    }

    private static String lastOperatorCode(List<PaymentTransition> history) {
        return history.isEmpty() ? "" : history.get(history.size() - 1).operatorCode();
    }
}
