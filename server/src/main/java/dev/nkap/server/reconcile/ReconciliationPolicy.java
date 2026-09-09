package dev.nkap.server.reconcile;

import java.time.Duration;
import java.time.Instant;

/**
 * The two time questions the reconciler asks, in one place: how long until the next
 * attempt, and have we been waiting too long.
 *
 * <p>They are answered by different things. The interval is a function of the attempt
 * count — attempts are counted from one, attempt 1 being the first retry after the payment
 * reached {@code UNKNOWN} — doubling from {@code backoffBase} and capped at
 * {@code backoffMax}. The window is a function of the <strong>clock</strong>: it is spent
 * once {@code window} of wall-clock time has passed since the payment entered
 * {@code UNKNOWN}, regardless of how many passes ran in between. An earlier version summed
 * the theoretical intervals of the attempts made and called that the elapsed time; the two
 * only agree while every pass runs on schedule, and {@code nkap.reconciler.window} is
 * configured as a {@code Duration} that an operator reads as wall-clock time.
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
     * Whether the retry window has elapsed — the point at which the reconciler stops
     * retrying and escalates instead.
     *
     * <p>{@code unknownSince} is when the payment entered {@code UNKNOWN}; {@code now} is
     * the current instant. The window is spent once {@code window} has passed between them.
     *
     * <p>A {@code null} {@code unknownSince} — a payment that predates the column and
     * slipped past its backfill — is treated as exhausted: a payment nobody escalates is
     * the exact outcome the reconciler exists to prevent, so the safe failure is to hand
     * it to a human on its next unresolved attempt rather than to leave it forever.
     */
    public boolean windowExhausted(Instant unknownSince, Instant now) {
        if (unknownSince == null) {
            return true;
        }
        return Duration.between(unknownSince, now).compareTo(window) >= 0;
    }
}
