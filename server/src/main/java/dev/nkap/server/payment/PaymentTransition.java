package dev.nkap.server.payment;

import dev.nkap.core.payment.PaymentState;
import java.time.Instant;
import java.util.Objects;

/**
 * One movement in a payment's life, and <strong>why</strong> it happened.
 *
 * <p>Every transition must be attributable — that is a roadmap line, not a nicety. A row
 * in the future {@code payment_transition} table looks exactly like this.
 *
 * @param from         the state before
 * @param to           the state after
 * @param at           when
 * @param cause        what triggered it
 * @param operatorCode the operator's machine-readable code when there was one, else {@code ""}
 * @param note         a short human-readable reason — the operator's message, an error text — else {@code ""}
 * @param rawResponse  the provider's response verbatim, else {@code ""}
 */
public record PaymentTransition(
        PaymentState from,
        PaymentState to,
        Instant at,
        Cause cause,
        String operatorCode,
        String note,
        String rawResponse) {

    /** What moved a payment. Only {@link #SUBMIT_RESPONSE} occurs in the first slice. */
    public enum Cause {
        SUBMIT_RESPONSE,
        QUERY,
        CALLBACK,
        RECONCILER
    }

    public PaymentTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(cause, "cause");
        operatorCode = operatorCode == null ? "" : operatorCode;
        note = note == null ? "" : note;
        rawResponse = rawResponse == null ? "" : rawResponse;
    }
}
