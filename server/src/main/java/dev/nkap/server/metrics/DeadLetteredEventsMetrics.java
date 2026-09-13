package dev.nkap.server.metrics;

import dev.nkap.server.outbox.OutboxRelayStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How many outbox events are currently dead-lettered, exported live on every
 * {@code /actuator/prometheus} scrape — the third alert worth waking someone for, beside the
 * suspense balance ({@link SuspenseBalanceMetrics}) and an escalated payment
 * ({@code Reconciler}). A dead-lettered event is the same kind of fact: the system has
 * stopped trying and a person now has to do something, and until this gauge existed that
 * fact sat in a column nobody watched.
 *
 * <p><strong>Computed at scrape time, not cached</strong> — the same reasoning as the
 * suspense balance: a stale computation should read as stale, not as a quietly repeated old
 * number. <strong>A failure to compute the count reports {@link Double#NaN}, never zero</strong>,
 * for the same reason a suspense-balance computation failure does: zero is the healthy value,
 * the one number a failure must never produce.
 */
@Configuration
class DeadLetteredEventsMetrics {

    static final String GAUGE_NAME = "nkap.outbox.dead_lettered";

    private static final Logger log = LoggerFactory.getLogger(DeadLetteredEventsMetrics.class);

    @Bean
    MeterBinder deadLetteredEventsGauge(OutboxRelayStore store) {
        return registry -> Gauge.builder(GAUGE_NAME, () -> countDeadLettered(store))
                .description("Outbox events the relay has given up retrying. Healthy at zero; "
                        + "NaN means the count itself could not be computed.")
                .register(registry);
    }

    private static double countDeadLettered(OutboxRelayStore store) {
        try {
            return store.countDeadLettered();
        } catch (RuntimeException computeFailed) {
            log.error("could not compute the dead-lettered event count; exporting NaN, not zero", computeFailed);
            return Double.NaN;
        }
    }
}
