package dev.nkap.server.payment;

import java.util.Arrays;

/**
 * Why the reconciler stopped retrying a payment automatically and paged a human. Stored with
 * the escalation ({@code payment.escalation_reason}), tagged on {@code nkap.payment.escalated}
 * and reported by {@code GET /actuator/escalatedPayments}, always as {@link #code()}.
 *
 * <p>Escalation is a flag, not a verdict: whatever the reason, the payment is still open, and a
 * later callback can still resolve it. The codes are listed here and nowhere else in code;
 * {@code docs/prometheus-alerts.yml} describes each one for whoever is paged.
 */
public enum EscalationReason {

    /** The retry window was spent without a conclusive answer from the operator. */
    WINDOW_EXHAUSTED("window_exhausted"),

    /**
     * The adapter cannot resolve a lost submission by polling and the payment has no provider
     * reference to query with, so no attempt could ever answer (ADR 0014 decision 3). Escalated
     * on the first pass that claims it, with no operator call.
     */
    CANNOT_QUERY("cannot_query"),

    /**
     * The retry window was spent while no adapter was configured for the payment's provider, so
     * the attempt that escalated it could not ask the operator (ADR 0016).
     */
    NO_ADAPTER("no_adapter");

    private final String code;

    EscalationReason(String code) {
        this.code = code;
    }

    /** The bounded, lower-case code used in storage, metrics and the management endpoint. */
    public String code() {
        return code;
    }

    /** The reason stored as {@code code}, or {@code null} for {@code null}; an unknown code throws. */
    public static EscalationReason ofCode(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(reason -> reason.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown escalation reason " + code));
    }
}
