package dev.nkap.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nkap.server.outbox.OutboxRelayStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third gauge worth waking someone for, beside the suspense balance and an escalated
 * payment (see {@link SuspenseBalanceMetrics}'s javadoc). Same shape, same test conventions:
 * a live count, and a computation failure that reports {@link Double#NaN}, never a
 * healthy-looking zero.
 */
class DeadLetteredEventsMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("the gauge tracks the live count of dead-lettered events")
    void the_gauge_tracks_the_live_count() {
        OutboxRelayStore store = mock(OutboxRelayStore.class);
        when(store.countDeadLettered()).thenReturn(0L, 1L, 3L);

        new DeadLetteredEventsMetrics().deadLetteredEventsGauge(store).bindTo(registry);
        Gauge gauge = registry.get("nkap.outbox.dead_lettered").gauge();

        assertThat(gauge.value()).as("nothing dead-lettered yet").isZero();
        assertThat(gauge.value()).as("one event dead-lettered").isEqualTo(1.0);
        assertThat(gauge.value()).as("more events dead-lettered").isEqualTo(3.0);
    }

    @Test
    @DisplayName("a failure to compute the count is distinguishable from an empty backlog — it reports NaN, not 0")
    void a_computation_failure_is_never_reported_as_a_healthy_zero() {
        OutboxRelayStore store = mock(OutboxRelayStore.class);
        when(store.countDeadLettered()).thenThrow(new RuntimeException("the database is unreachable"));

        new DeadLetteredEventsMetrics().deadLetteredEventsGauge(store).bindTo(registry);
        double value = registry.get("nkap.outbox.dead_lettered").gauge().value();

        assertThat(Double.isNaN(value)).as("a computation failure is NaN, not a number at all").isTrue();
        assertThat(value).as("NaN is never equal to zero, by definition").isNotEqualTo(0.0);
    }
}
