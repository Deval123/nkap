package dev.nkap.core.payment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a payment, and the only place transitions are decided.
 *
 * <p>The rule that shapes everything else: <strong>a timeout is not a failure.</strong>
 * A call that did not answer moves to {@link #UNKNOWN}, never to {@link #FAILED}.
 * {@code UNKNOWN} is not terminal — the reconciler resolves it, or a human is paged.
 * No code in this system may conclude that a payment failed without the provider having
 * said so explicitly.
 */
public enum PaymentState {

    /** Reference generated and persisted. Nothing has been sent yet. */
    CREATED,

    /** The provider acknowledged receipt. No money has moved. */
    SUBMITTED,

    /** The payer must approve on their handset. This can take minutes. */
    PENDING,

    /**
     * A timeout, a 5xx, or a response that could not be read. The outcome is genuinely
     * unknown and must be established, not assumed.
     */
    UNKNOWN,

    /** The provider explicitly confirmed the money moved. Terminal. */
    SUCCEEDED,

    /** The provider explicitly confirmed the payment did not go through. Terminal. */
    FAILED,

    /** The payer never approved within the provider's window. Terminal. */
    EXPIRED;

    private static final Map<PaymentState, Set<PaymentState>> ALLOWED;

    static {
        Map<PaymentState, Set<PaymentState>> allowed = new EnumMap<>(PaymentState.class);
        allowed.put(CREATED, EnumSet.of(SUBMITTED, UNKNOWN, FAILED));
        allowed.put(SUBMITTED, EnumSet.of(PENDING, SUCCEEDED, FAILED, EXPIRED, UNKNOWN));
        allowed.put(PENDING, EnumSet.of(SUCCEEDED, FAILED, EXPIRED, UNKNOWN));
        allowed.put(UNKNOWN, EnumSet.of(SUCCEEDED, FAILED, EXPIRED, PENDING));
        allowed.put(SUCCEEDED, EnumSet.noneOf(PaymentState.class));
        allowed.put(FAILED, EnumSet.noneOf(PaymentState.class));
        allowed.put(EXPIRED, EnumSet.noneOf(PaymentState.class));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    /** A terminal state has no outgoing transitions and its ledger effect is settled. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Whether this state still needs the reconciler to look at it. */
    public boolean needsResolution() {
        return this == UNKNOWN;
    }

    /** Only SUCCEEDED writes to the ledger, and only once. */
    public boolean movesMoney() {
        return this == SUCCEEDED;
    }

    public Set<PaymentState> allowedNext() {
        return ALLOWED.get(this);
    }

    public boolean canTransitionTo(PaymentState next) {
        return next != null && ALLOWED.get(this).contains(next);
    }

    /**
     * Returns {@code next} if the transition is legal, and throws otherwise.
     *
     * <p>Callers use the returned value rather than their own, so that an illegal
     * transition cannot be silently ignored.
     */
    public PaymentState transitionTo(PaymentState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalTransitionException(this, next);
        }
        return next;
    }

    /**
     * The state a payment moves to when a provider call did not answer.
     *
     * <p>Exists as a named method so that "timeout means unknown, not failed" is a rule
     * with one home rather than a convention repeated at every call site.
     */
    public PaymentState onProviderTimeout() {
        return isTerminal() ? this : transitionTo(UNKNOWN);
    }
}
