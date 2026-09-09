package dev.nkap.server.reconcile;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the reconciler paces itself. Bound from {@code nkap.reconciler.*}; the defaults and
 * what each one costs when it is wrong are in {@code application.yml}.
 *
 * @param interval    how often the scheduled pass runs
 * @param batchSize   how many due payments one pass claims — a bound so a backlog cannot
 *                    turn one pass into a storm of operator calls
 * @param backoffBase the delay before the first retry; each subsequent retry doubles it
 * @param backoffMax  the ceiling on a single interval, so backoff does not grow without limit
 * @param window      the total time to keep retrying a payment before escalating it to a human
 */
@ConfigurationProperties("nkap.reconciler")
public record ReconcilerProperties(
        Duration interval,
        int batchSize,
        Duration backoffBase,
        Duration backoffMax,
        Duration window) {

    public ReconcilerProperties {
        requirePositive(interval, "interval");
        requirePositive(backoffBase, "backoffBase");
        requirePositive(backoffMax, "backoffMax");
        requirePositive(window, "window");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("nkap.reconciler.batch-size must be positive, was " + batchSize);
        }
        if (backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException(
                    "nkap.reconciler.backoff-max (" + backoffMax + ") must be at least backoff-base (" + backoffBase + ")");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, "nkap.reconciler." + name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("nkap.reconciler." + name + " must be positive, was " + value);
        }
    }
}
