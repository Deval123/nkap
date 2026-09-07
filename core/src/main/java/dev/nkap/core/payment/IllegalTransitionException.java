package dev.nkap.core.payment;

/** Thrown when code attempts a payment state change the machine does not allow. */
public class IllegalTransitionException extends IllegalStateException {

    private final PaymentState from;
    private final PaymentState to;

    public IllegalTransitionException(PaymentState from, PaymentState to) {
        super("Cannot move a payment from " + from + " to " + to
                + (from.isTerminal() ? ": " + from + " is terminal" : ""));
        this.from = from;
        this.to = to;
    }

    public PaymentState from() {
        return from;
    }

    public PaymentState to() {
        return to;
    }
}
