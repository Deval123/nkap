package dev.nkap.server.web;

import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.outbox.OutboxRelayStore;
import dev.nkap.server.outbox.OutboxRelayStore.StoredEvent;
import dev.nkap.server.outbox.WebhookSender;
import dev.nkap.server.webhook.WebhookEndpoint;
import dev.nkap.server.webhook.WebhookEndpointStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhooks/events/{eventId}/replay} — the roadmap's "replay from the console or
 * the API" line, and the API half of it.
 *
 * <p>A replay writes nothing to the ledger, so the statement-import argument for keeping a
 * write off HTTP entirely does not apply with full force — but it still triggers an outbound
 * call in a merchant's name, to whatever URL is on file for it, so this is gated behind an
 * <strong>admin</strong> credential, the same gate {@code GET /statements/imports/{id}} and
 * {@code GET /balance} use, rather than left open to a merchant key or unauthenticated the
 * way the callback endpoint is: a callback only ever triggers a confirming read, this
 * triggers a write to a third party.
 *
 * <p>Deliberately does not touch the original {@code outbox_event} row's bookkeeping —
 * {@code attempts}, {@code delivered_at}, {@code dead_lettered_at} stay exactly as the relay
 * left them. A replay is a manual, out-of-band resend for a human who has already looked at
 * why an event failed (or wants a merchant to re-process one that did not), not a retry the
 * relay's own schedule should account for; conflating the two would let a replay reset a
 * dead-lettered event's exhaustion count without anyone deciding that on purpose.
 */
@RestController
@RequestMapping("/webhooks/events")
class WebhookReplayController {

    private final OutboxRelayStore store;
    private final WebhookEndpointStore endpoints;
    private final WebhookSender sender;

    WebhookReplayController(OutboxRelayStore store, WebhookEndpointStore endpoints, WebhookSender sender) {
        this.store = store;
        this.endpoints = endpoints;
        this.sender = sender;
    }

    @PostMapping("/{eventId}/replay")
    WebhookReplayResponse replay(ApiCredential caller, @PathVariable String eventId) {
        requireAdmin(caller);
        UUID id = parseId(eventId);
        StoredEvent event = store.find(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ProblemTypes.EVENT_NOT_FOUND,
                "No such event", "No outbox event has id " + eventId + "."));
        WebhookEndpoint endpoint = endpoints.find(event.merchantId()).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ProblemTypes.NO_WEBHOOK_ENDPOINT,
                "No webhook endpoint",
                "Merchant '" + event.merchantId() + "' has no registered webhook endpoint to replay this event to."));

        WebhookSender.DeliveryResult result = sender.send(endpoint, event.id(), event.eventType(), event.payload());
        return new WebhookReplayResponse(event.id().toString(), result.delivered(), result.error());
    }

    private static void requireAdmin(ApiCredential caller) {
        if (!caller.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ProblemTypes.ADMIN_REQUIRED,
                    "An admin key is required",
                    "Replaying a webhook triggers a call in a merchant's name. This key is a merchant key.");
        }
    }

    private static UUID parseId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.EVENT_NOT_FOUND,
                    "No such event", "'" + eventId + "' is not an event id.");
        }
    }
}
