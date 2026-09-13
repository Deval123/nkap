package dev.nkap.server.outbox;

import dev.nkap.server.outbox.OutboxRelayStore.Claim;
import dev.nkap.server.webhook.WebhookEndpoint;
import dev.nkap.server.webhook.WebhookEndpointStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Delivers what {@link OutboxNotifier} wrote, at least once.
 *
 * <p>Claims a bounded batch of due events with {@code FOR UPDATE SKIP LOCKED}, advances each
 * one's schedule in that same claim transaction, then sends outside it — {@code Reconciler}
 * already shows this shape, for the same reason: an unreachable receiver must not hold a
 * database transaction open for the whole request timeout.
 *
 * <p><strong>This stayed a second implementation of that shape rather than sharing one with
 * {@code Reconciler}.</strong> The claim-advance-call skeleton is identical, but what happens
 * after the call is not: the reconciler asks the same question again indefinitely and only
 * ever <em>escalates</em> — a payment is never abandoned, a human is paged, and the payment
 * stays open for a later callback or manual resolution. This class <em>dead-letters</em> —
 * after a bounded number of attempts, delivery genuinely stops, and what is dead-lettered is
 * found and replayed from a merchant's console (or the admin replay endpoint), not paged to a
 * human. Those are different lifecycles wearing the same skeleton, not one mechanism with two
 * configurations — extracting a shared "claim, advance, call" abstraction for two call sites
 * with different endings would have been the premature generalisation, not the missing one.
 *
 * <p>Each pass gets its own id, carried as a structured log field for the pass's whole
 * duration, and each delivery attempt its own {@code eventId} field nested inside it — the
 * same correlated-logs shape issue #75 gave the reconciler.
 *
 * <p>Built and scheduled by {@link OutboxConfiguration}, switched off by
 * {@code nkap.webhooks.enabled=false} the same way {@code nkap.reconciler.enabled=false}
 * switches off the reconciler.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRelayStore store;
    private final WebhookEndpointStore endpoints;
    private final WebhookSender sender;
    private final OutboxRelayPolicy policy;
    private final OutboxRelayProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxRelayStore store, WebhookEndpointStore endpoints, WebhookSender sender,
                       OutboxRelayPolicy policy, OutboxRelayProperties properties, Clock clock) {
        this.store = store;
        this.endpoints = endpoints;
        this.sender = sender;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nkap.webhooks.interval}")
    void scheduledPass() {
        try {
            runOnce();
        } catch (RuntimeException failed) {
            // A pass that blew up must not kill the scheduler thread: the next pass will
            // re-claim whatever this one left, its schedule already advanced.
            log.error("outbox relay pass failed; the next pass will retry the same events", failed);
        }
    }

    /** One pass: claim the due batch, attempt each. Returns how many events were claimed. */
    public int runOnce() {
        String passId = UUID.randomUUID().toString();
        try (var ignored = MDC.putCloseable("outboxRelayPass", passId)) {
            List<Claim> claims = store.claimDue(properties.batchSize(), clock.instant());
            log.debug("outbox relay pass {} claimed {} event(s)", passId, claims.size());
            for (Claim claim : claims) {
                deliver(claim);
            }
            return claims.size();
        }
    }

    private void deliver(Claim claim) {
        try (var ignored = MDC.putCloseable("eventId", claim.id().toString())) {
            Optional<WebhookEndpoint> endpoint = endpoints.find(claim.merchantId());
            if (endpoint.isEmpty()) {
                // The merchant removed its endpoint after this event was written. Retrying
                // will not change that — there is nowhere to send it — so this dead-letters
                // on the spot rather than spending the full retry budget finding out the
                // same thing again every time.
                store.recordFailure(claim.id(), "no webhook endpoint configured for merchant " + claim.merchantId(),
                        true, clock.instant());
                log.warn("event {} dead-lettered: no webhook endpoint for merchant {}", claim.id(), claim.merchantId());
                return;
            }

            WebhookSender.DeliveryResult result = sender.send(endpoint.get(), claim.id(), claim.eventType(), claim.payload());
            Instant now = clock.instant();
            if (result.delivered()) {
                store.markDelivered(claim.id(), now);
                log.info("delivered event {} ({}) to {}", claim.id(), claim.eventType(), endpoint.get().url());
                return;
            }

            boolean deadLetter = policy.attemptsExhausted(claim.attempts());
            store.recordFailure(claim.id(), result.error(), deadLetter, now);
            if (deadLetter) {
                log.warn("event {} dead-lettered after {} attempt(s): {}", claim.id(), claim.attempts(), result.error());
            } else {
                log.info("event {} delivery attempt {} failed, retrying: {}", claim.id(), claim.attempts(), result.error());
            }
        }
    }
}
