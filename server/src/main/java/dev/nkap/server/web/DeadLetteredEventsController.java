package dev.nkap.server.web;

import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.outbox.OutboxRelayStore;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /webhooks/events/dead-lettered} — the read half of dead-lettering that shipped
 * without it. {@code dead_lettered_at} was written and nothing ever read it back; the only
 * route in was {@code POST /webhooks/events/{eventId}/replay}, which requires already knowing
 * the id. This is the same rule {@code PaymentRepository.findEscalated()} follows for a
 * payment the reconciler gave up on: stop trying, make it findable, never decide it did not
 * matter.
 *
 * <p>Admin-gated, the same credential class {@code GET /statements/imports/{id}} and
 * {@code GET /balance} use: this is operator data across merchants, not a merchant's own.
 */
@RestController
@RequestMapping("/webhooks/events")
class DeadLetteredEventsController {

    private final OutboxRelayStore store;

    DeadLetteredEventsController(OutboxRelayStore store) {
        this.store = store;
    }

    @GetMapping("/dead-lettered")
    List<DeadLetteredEventResponse> list(ApiCredential caller) {
        if (!caller.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ProblemTypes.ADMIN_REQUIRED,
                    "An admin key is required",
                    "Dead-lettered events are operator data across all merchants. This key is a merchant key.");
        }
        return store.findDeadLettered().stream()
                .map(event -> new DeadLetteredEventResponse(
                        event.id().toString(), event.merchantId(), event.eventType(),
                        event.lastError(), event.deadLetteredAt()))
                .toList();
    }
}
