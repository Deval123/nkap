package dev.nkap.server.web;

import java.time.Instant;

/**
 * One dead-lettered event, as listed by {@code GET /webhooks/events/dead-lettered}. Carries
 * enough to triage without a database: which merchant, what kind of event, why the relay gave
 * up, and when.
 */
public record DeadLetteredEventResponse(
        String eventId, String merchantId, String eventType, String lastError, Instant deadLetteredAt) {
}
