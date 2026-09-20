package dev.nkap.server.outbox;

/**
 * The body of a payment webhook, exactly as a merchant receives it. Every field here is
 * part of the contract described in {@code docs/webhooks.md} — renaming or removing one is
 * a breaking change to every integration, not a refactor.
 *
 * @param id                    this event's id — stable across retries, the field a merchant
 *                              deduplicates on (at-least-once delivery, no ordering guarantee)
 * @param type                  {@code payment.succeeded}, {@code payment.failed}, {@code payment.expired}, or —
 *                              for a refund (issue #84) — {@code refund.succeeded}, {@code refund.failed},
 *                              {@code refund.expired}
 * @param reference             the payment's reference, the same value {@code GET /payments/{reference}} takes
 * @param provider              which operator, e.g. {@code mtn}
 * @param operation             {@code COLLECT} or {@code DISBURSE} — a refund is a {@code DISBURSE}; {@code type}
 *                              and {@code refundOf} are how a receiver tells it apart from an ordinary one
 * @param amountMinor           the payment's amount, as an integer count of minor units — never a decimal
 * @param currency              the payment's currency
 * @param state                 the terminal state this event announces — matches {@code type}
 * @param cause                 what attributed the transition this event announces — one of
 *                              {@code PaymentTransition.Cause}, e.g. {@code SUBMIT_RESPONSE},
 *                              {@code CALLBACK}, {@code QUERY}, {@code RECONCILER} or
 *                              {@code GATEWAY}. Present on every event, not only a failure: a
 *                              {@code payment.succeeded} carrying {@code CALLBACK} says the
 *                              operator's own webhook settled it, not a later reconciler pass.
 *                              For a {@code payment.failed} or {@code refund.failed} it is what
 *                              a receiver needs to tell the two kinds of refusal apart —
 *                              {@code SUBMIT_RESPONSE} means the operator was asked and
 *                              refused; {@code GATEWAY} means this gateway refused the request
 *                              itself and never asked the operator anything (ADR 0013). The
 *                              two call for different reactions: an operator refusal is about
 *                              the payment and may be worth telling a payer about; a gateway
 *                              refusal is about the request — a client bug or a misconfigured
 *                              installation — and is not.
 * @param providerCode          the operator's own code for the terminal transition, or {@code ""}
 * @param providerTransactionId the operator's transaction id, set only for a {@code succeeded} event, else {@code ""}
 * @param occurredAt            when the payment reached this state, ISO-8601 ({@code Instant.toString()}) —
 *                              a string, not a numeric type, on purpose: it needs no shared convention about
 *                              units (seconds vs. millis) with whatever language a merchant's receiver is written in
 * @param refundOf              the original collection's reference, for a refund event; {@code ""} otherwise
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
        String cause,
        String providerCode,
        String providerTransactionId,
        String occurredAt,
        String refundOf) {
}
