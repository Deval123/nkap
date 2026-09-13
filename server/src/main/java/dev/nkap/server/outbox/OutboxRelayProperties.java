package dev.nkap.server.outbox;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the outbox relay paces itself. Bound from {@code nkap.webhooks.*}; the defaults and
 * what each one costs when it is wrong are in {@code application.yml}.
 *
 * @param interval           how often the scheduled pass runs
 * @param batchSize          how many due events one pass claims — a bound, for the same
 *                           reason the reconciler's is
 * @param backoffBase        the delay before the first retry; each subsequent retry doubles it
 * @param backoffMax         the ceiling on a single interval
 * @param maxAttempts        the attempt count past which an event is dead-lettered rather
 *                           than retried again
 * @param requestTimeout     how long one delivery attempt may take before it counts as a
 *                           failure — bounded so a slow receiver cannot stall a whole pass
 * @param signatureTolerance how far a signed request's timestamp may drift from "now" before
 *                           a receiver following {@code WebhookSigner.verify} must refuse it —
 *                           not read by this gateway, which only signs; carried here so it is
 *                           configured alongside everything else about a delivery and
 *                           documented in one place ({@code docs/webhooks.md})
 */
@ConfigurationProperties("nkap.webhooks")
public record OutboxRelayProperties(
        Duration interval,
        int batchSize,
        Duration backoffBase,
        Duration backoffMax,
        int maxAttempts,
        Duration requestTimeout,
        Duration signatureTolerance) {

    public OutboxRelayProperties {
        requirePositive(interval, "interval");
        requirePositive(backoffBase, "backoffBase");
        requirePositive(backoffMax, "backoffMax");
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(signatureTolerance, "signatureTolerance");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("nkap.webhooks.batch-size must be positive, was " + batchSize);
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("nkap.webhooks.max-attempts must be positive, was " + maxAttempts);
        }
        if (backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException(
                    "nkap.webhooks.backoff-max (" + backoffMax + ") must be at least backoff-base (" + backoffBase + ")");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, "nkap.webhooks." + name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("nkap.webhooks." + name + " must be positive, was " + value);
        }
    }
}
