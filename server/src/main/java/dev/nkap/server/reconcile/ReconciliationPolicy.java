package dev.nkap.server.reconcile;

import java.time.Duration;

/**
 * The backoff arithmetic, in one place: when the next attempt is due, and when the window
 * is spent and the payment must be escalated.
 *
 * <p>Attempts are counted from one — attempt 1 is the first retry after the payment
 * reached {@code UNKNOWN}. The interval doubles each attempt from {@code backoffBase} and
 * is capped at {@code backoffMax}. The window is spent once the intervals scheduled across
 * the attempts made so far add up to at least {@code window}; expressing it as a function
 * of the attempt count alone keeps escalation decidable without a "first seen" timestamp.
 */
public final class ReconciliationPolicy {

    /** Past this exponent the interval has long since pinned to backoffMax; the shift is just kept safe. */
    private static final int MAX_SHIFT = 32;

    private final Duration backoffBase;
    private final Duration backoffMax;
    private final Duration window;

    public ReconciliationPolicy(ReconcilerProperties properties) {
        this(properties.backoffBase(), properties.backoffMax(), properties.window());
    }

    public ReconciliationPolicy(Duration backoffBase, Duration backoffMax, Duration window) {
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.window = window;
    }

    /** The delay before attempt number {@code attempt} (1 = the first retry). */
    public Duration intervalForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt is counted from 1, was " + attempt);
        }
        long factor = 1L << Math.min(attempt - 1, MAX_SHIFT);
        Duration doubled = backoffBase.multipliedBy(factor);
        return doubled.compareTo(backoffMax) >= 0 ? backoffMax : doubled;
    }

    /**
     * Whether {@code attemptsMade} attempts have used up the retry window — the point at
     * which the reconciler stops and escalates instead.
     */
    public boolean windowExhausted(int attemptsMade) {
        Duration cumulative = Duration.ZERO;
        for (int attempt = 1; attempt <= attemptsMade; attempt++) {
            cumulative = cumulative.plus(intervalForAttempt(attempt));
            if (cumulative.compareTo(window) >= 0) {
                return true;
            }
        }
        return false;
    }
}
