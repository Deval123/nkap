package dev.nkap.server.metrics;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.provider.ProviderRouting;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The suspense balance, exported live on every {@code /actuator/prometheus} scrape — the
 * single most useful alert in the system (roadmap §4). The statement importer posts to
 * {@code suspense:<provider>:<CCY>} when the operator says money moved and Nkap cannot
 * attribute it; before this class, nothing watched that balance.
 *
 * <p>One gauge per {@link ProviderRouting} this installation has configured, tagged
 * {@code provider} and {@code currency} — never a reference, an MSISDN or a credential; see
 * the class-level warning on why those never become a label.
 *
 * <p><strong>Computed at scrape time, not cached.</strong> {@link Ledger#balance} sums the
 * account's postings on every read, so the exported value is never older than the scrape
 * that asked for it. This is what makes staleness visible instead of something an operator
 * has to notice is missing: a gauge whose data source has gone stale is a gauge that answers
 * wrong the moment it is asked, not one that quietly keeps repeating the last good number.
 *
 * <p><strong>A failure to compute the balance reports {@link Double#NaN}, never zero.</strong>
 * Zero is the healthy value for a suspense account — the one number a failure must never
 * produce, because it would read as "nothing is wrong" to whoever is watching. By ordinary
 * IEEE 754 comparison rules NaN is never equal to zero, so {@code docs/prometheus-alerts.yml}'s
 * rule — {@code nkap_suspense_balance != 0} — catches a stuck computation failure the same
 * way it catches a genuinely non-zero balance: both need a human, and neither reads as
 * healthy. What this gauge does not cover is the scrape failing outright (the target going
 * unreachable) — that is a separate, standard concern (Prometheus's own {@code up} metric,
 * or {@code absent_over_time}), not something a value from inside the process can express.
 */
@Configuration
class SuspenseBalanceMetrics {

    static final String GAUGE_NAME = "nkap.suspense.balance";

    private static final Logger log = LoggerFactory.getLogger(SuspenseBalanceMetrics.class);

    /**
     * One gauge per configured provider routing. A {@link MeterBinder} bean rather than
     * registering directly against an injected {@code MeterRegistry}: Spring Boot Actuator
     * calls {@code bindTo} on every such bean once the registry exists, which is also what
     * lets a test bind this against a bare {@code SimpleMeterRegistry} with no Spring
     * context at all.
     */
    @Bean
    MeterBinder suspenseBalanceGauges(Ledger ledger, List<ProviderRouting> routes) {
        return registry -> {
            for (ProviderRouting route : routes) {
                Gauge.builder(GAUGE_NAME, () -> suspenseBalance(ledger, route.provider(), route.currency()))
                        .description("Money the operator confirmed moved that Nkap could not attribute. "
                                + "Healthy at zero; NaN means the balance itself could not be computed.")
                        .tag("provider", route.provider().toString())
                        .tag("currency", route.currency().name())
                        .register(registry);
            }
        };
    }

    private static double suspenseBalance(Ledger ledger, ProviderId provider, Currency currency) {
        try {
            return ledger.balance(AccountId.suspense(provider.toString(), currency), currency).amount();
        } catch (RuntimeException computeFailed) {
            log.error("could not compute the suspense balance for {}/{}; exporting NaN, not zero",
                    provider, currency, computeFailed);
            return Double.NaN;
        }
    }
}
