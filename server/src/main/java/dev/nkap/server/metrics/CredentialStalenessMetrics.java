package dev.nkap.server.metrics;

import dev.nkap.provider.ProviderId;
import dev.nkap.server.provider.CredentialStaleness;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How long an installation has been paying with credentials its files no longer describe (issue
 * #223, ADR 0015), exported on every {@code /actuator/prometheus} scrape.
 *
 * <p>A rotated credential file that is unreadable or invalid does not stop payments: the gateway
 * keeps the last valid credentials and serves with them. That is only acceptable if the stale use
 * is visible, and one warning in a container log is not. This gauge is the visibility. It is zero
 * while the credentials in use are the ones on disk, and the number of seconds since the files
 * were first found rejected otherwise, so {@code nkap_credentials_stale_seconds > 0} is the alert.
 *
 * <p>Computed at scrape time: each scrape looks at the files, through the same modification-time
 * gate a payment uses, so the gauge moves even when no payment has asked since the files changed.
 * Recovery logs nothing; this returning to zero is the signal.
 *
 * <p>One gauge per installation that re-reads a credential file, tagged {@code provider}. An
 * installation configured by variables alone cannot go stale and has none. Never a credential, a
 * file name or a path as a label.
 */
@Configuration
class CredentialStalenessMetrics {

    static final String GAUGE_NAME = "nkap.credentials.stale";

    /** A {@link MeterBinder} bean for the reason {@link SuspenseBalanceMetrics} gives. */
    @Bean
    MeterBinder credentialStalenessGauges(ObjectProvider<CredentialStaleness> staleness) {
        return registry -> staleness.orderedStream().forEach(source -> gauges(source).bindTo(registry));
    }

    static MeterBinder gauges(CredentialStaleness staleness) {
        return registry -> {
            for (ProviderId installation : staleness.installations()) {
                Gauge.builder(GAUGE_NAME, () -> staleness.staleFor(installation).toMillis() / 1000.0)
                        .description("Seconds since this installation's credential files were first found unreadable"
                                + " or invalid, while payments keep using the last valid credentials. Healthy at zero.")
                        .baseUnit("seconds")
                        .tag("provider", installation.toString())
                        .register(registry);
            }
        };
    }
}
