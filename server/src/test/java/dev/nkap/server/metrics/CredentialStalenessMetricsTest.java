package dev.nkap.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.provider.ProviderId;
import dev.nkap.server.provider.CredentialStaleness;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CredentialStalenessMetricsTest {

    private static final ProviderId KENYA = ProviderId.of("mpesa-ke");

    @Test
    @DisplayName("the gauge is zero while the credentials in use are the ones on disk, and counts seconds while they are not")
    void the_gauge_reports_seconds_of_stale_use() {
        AtomicReference<Duration> stale = new AtomicReference<>(Duration.ZERO);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CredentialStalenessMetrics.gauges(new CredentialStaleness(Map.of(KENYA, stale::get))).bindTo(registry);
        Gauge gauge = registry.get(CredentialStalenessMetrics.GAUGE_NAME).tag("provider", "mpesa-ke").gauge();

        assertThat(gauge.value()).isZero();
        stale.set(Duration.ofMillis(95_500));
        assertThat(gauge.value()).as("read at scrape time, not cached").isEqualTo(95.5);
        stale.set(Duration.ZERO);
        assertThat(gauge.value()).as("recovery is the gauge returning to zero").isZero();
    }

    @Test
    @DisplayName("an installation that re-reads no credential file has no gauge")
    void no_rereading_installation_means_no_gauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CredentialStalenessMetrics.gauges(new CredentialStaleness(Map.of())).bindTo(registry);

        assertThat(registry.find(CredentialStalenessMetrics.GAUGE_NAME).gauges()).isEmpty();
    }

    @Test
    @DisplayName("the gauge is tagged by provider id and nothing else, and exported in seconds")
    void the_gauge_carries_only_the_provider() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CredentialStalenessMetrics.gauges(new CredentialStaleness(Map.of(KENYA, () -> Duration.ZERO))).bindTo(registry);
        Gauge gauge = registry.get(CredentialStalenessMetrics.GAUGE_NAME).gauge();

        assertThat(gauge.getId().getTags()).extracting(t -> t.getKey()).containsExactly("provider");
        assertThat(gauge.getId().getBaseUnit()).isEqualTo("seconds");
    }
}
