package dev.nkap.server.outbox;

import java.time.Duration;

/**
 * The two questions {@link OutboxRelay} asks about a delivery attempt: how long until the
 * next one, and has the event had enough of them. The interval math is the same shape as
 * {@code ReconciliationPolicy.intervalForAttempt} — doubling from {@code backoffBase},
 * capped at {@code backoffMax} — because it is the same idea (a struggling receiver should
 * be asked less often, not hammered), not because the two classes were merged into one; see
 * {@code OutboxRelay}'s javadoc for why they stayed separate.
 *
 * <p>Where this differs from the reconciler's policy is the exhaustion test: the reconciler
 * escalates on <strong>wall-clock time</strong> since a payment became unresolved, because a
 * payment must never be abandoned — a human always eventually looks. An outbox event
 * dead-letters on an <strong>attempt count</strong> instead: retrying an unreachable receiver
 * forever, across an outage that might last days, is not the same failure mode as escalating
 * a stuck payment, and a merchant's console is where a dead-lettered event is found and
 * replayed, not a human's pager.
 */
public final class OutboxRelayPolicy {

    private static final int MAX_SHIFT = 32;

    private final Duration backoffBase;
    private final Duration backoffMax;
    private final int maxAttempts;

    public OutboxRelayPolicy(OutboxRelayProperties properties) {
        this(properties.backoffBase(), properties.backoffMax(), properties.maxAttempts());
    }

    public OutboxRelayPolicy(Duration backoffBase, Duration backoffMax, int maxAttempts) {
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.maxAttempts = maxAttempts;
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

    /** Whether {@code attempts} made is enough to stop retrying and dead-letter the event. */
    public boolean attemptsExhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
