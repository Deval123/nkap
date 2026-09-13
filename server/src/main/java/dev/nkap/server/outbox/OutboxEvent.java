package dev.nkap.server.outbox;

import java.util.Objects;
import java.util.UUID;

/**
 * One event to write to the outbox, already built and serialised — this record carries no
 * behaviour, only what {@link Outbox#append} needs to write the row.
 *
 * @param id         the event's own id, stable for its whole life. This is what a merchant
 *                   deduplicates on: at-least-once delivery means the same id can arrive
 *                   more than once, and this id is the contract that makes that safe.
 * @param merchantId whose event this is — the merchant {@link OutboxRelay} looks up an
 *                   endpoint for
 * @param eventType  e.g. {@code payment.succeeded} — see {@link OutboxNotifier}
 * @param payload    the JSON body a receiver gets, verbatim
 */
public record OutboxEvent(UUID id, String merchantId, String eventType, String payload) {

    public OutboxEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
    }
}
