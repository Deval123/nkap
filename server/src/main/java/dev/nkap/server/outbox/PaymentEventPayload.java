package dev.nkap.server.outbox;

/**
 * The body of a payment webhook, exactly as a merchant receives it. Every field here is
 * part of the contract described in {@code docs/webhooks.md} — renaming or removing one is
 * a breaking change to every integration, not a refactor.
 *
 * @param id                    this event's id — stable across retries, the field a merchant
 *                              deduplicates on (at-least-once delivery, no ordering guarantee)
 * @param type                  {@code payment.succeeded}, {@code payment.failed} or {@code payment.expired}
 * @param reference             the payment's reference, the same value {@code GET /payments/{reference}} takes
 * @param provider              which operator, e.g. {@code mtn}
 * @param operation             {@code COLLECT} or {@code DISBURSE}
 * @param amountMinor           the payment's amount, as an integer count of minor units — never a decimal
 * @param currency              the payment's currency
 * @param state                 the terminal state this event announces — matches {@code type}
 * @param providerCode          the operator's own code for the terminal transition, or {@code ""}
 * @param providerTransactionId the operator's transaction id, set only for {@code payment.succeeded}, else {@code ""}
 * @param occurredAt            when the payment reached this state, ISO-8601 ({@code Instant.toString()}) —
 *                              a string, not a numeric type, on purpose: it needs no shared convention about
 *                              units (seconds vs. millis) with whatever language a merchant's receiver is written in
 */
public record PaymentEventPayload(
        String id,
        String type,
        String reference,
        String provider,
        String operation,
        long amountMinor,
        String currency,
        String state,
        String providerCode,
        String providerTransactionId,
        String occurredAt) {
}
