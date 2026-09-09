package dev.nkap.server.payment;

import dev.nkap.core.payment.PaymentState;

/**
 * What {@link SettlementService#confirm} did, for the caller that needs to react to it.
 *
 * <p>The callback endpoint discards this — a webhook is fire-and-forget. The reconciler
 * reads it: whether the payment is still {@code UNKNOWN} decides whether this pass counts
 * as a spent attempt, and {@link #lastOperatorAnswer()} is what the escalation log records
 * as the operator's final word before a human was paged.
 *
 * @param kind               what happened
 * @param state              the payment's state after the call, or {@code null} when the payment
 *                           was not found
 * @param operatorStatusCode the operator's status code from the confirming query, or {@code ""}
 */
public record ConfirmationOutcome(Kind kind, PaymentState state, String operatorStatusCode) {

    public enum Kind {
        /** The confirming query was conclusive and the payment moved to a terminal state. */
        RESOLVED,
        /** The payment was already terminal before the query — nothing to do, and nothing wrong. */
        ALREADY_RESOLVED,
        /** The query answered, but not conclusively: the payment is still {@code UNKNOWN}. */
        INCONCLUSIVE,
        /** The operator did not answer the confirming query. The payment is unchanged. */
        NO_ANSWER,
        /** This gateway does not hold a payment for that reference. */
        NOT_HELD
    }

    public ConfirmationOutcome {
        operatorStatusCode = operatorStatusCode == null ? "" : operatorStatusCode;
    }

    /** The payment is no longer this gateway's problem to chase: it reached, or had reached, a verdict. */
    public boolean resolved() {
        return kind == Kind.RESOLVED || kind == Kind.ALREADY_RESOLVED;
    }

    /** The reconciler asked and is no wiser: this pass was a spent attempt. */
    public boolean inconclusive() {
        return kind == Kind.INCONCLUSIVE || kind == Kind.NO_ANSWER;
    }

    /** A short phrase for the escalation log: the operator's last word, or that there was none. */
    public String lastOperatorAnswer() {
        return switch (kind) {
            case NO_ANSWER -> "no answer";
            case INCONCLUSIVE -> operatorStatusCode.isBlank() ? "UNKNOWN" : operatorStatusCode;
            default -> operatorStatusCode.isBlank() ? String.valueOf(state) : operatorStatusCode;
        };
    }

    static ConfirmationOutcome notHeld() {
        return new ConfirmationOutcome(Kind.NOT_HELD, null, "");
    }

    static ConfirmationOutcome alreadyResolved(PaymentState state) {
        return new ConfirmationOutcome(Kind.ALREADY_RESOLVED, state, "");
    }

    static ConfirmationOutcome noAnswer() {
        return new ConfirmationOutcome(Kind.NO_ANSWER, PaymentState.UNKNOWN, "");
    }

    static ConfirmationOutcome inconclusive(PaymentState state, String operatorStatusCode) {
        return new ConfirmationOutcome(Kind.INCONCLUSIVE, state, operatorStatusCode);
    }

    static ConfirmationOutcome resolved(PaymentState state, String operatorStatusCode) {
        return new ConfirmationOutcome(Kind.RESOLVED, state, operatorStatusCode);
    }
}
