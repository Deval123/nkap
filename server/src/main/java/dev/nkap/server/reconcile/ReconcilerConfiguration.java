package dev.nkap.server.reconcile;

import dev.nkap.server.payment.SettlementService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the reconciler and turns on {@code @Scheduled}. Everything it builds is plain
 * construction — the policy from the bound properties, the store from the same
 * {@link JdbcTemplate} and {@link PlatformTransactionManager} the other stores use.
 *
 * <p>{@code nkap.reconciler.enabled=false} switches the whole thing off, scheduler
 * included: the integration tests that are not about the reconciler set that so a
 * background pass cannot move a payment out from under an assertion.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReconcilerProperties.class)
@ConditionalOnProperty(prefix = "nkap.reconciler", name = "enabled", matchIfMissing = true)
class ReconcilerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReconciliationPolicy reconciliationPolicy(ReconcilerProperties properties) {
        return new ReconciliationPolicy(properties);
    }

    @Bean
    ReconciliationStore reconciliationStore(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                            ReconciliationPolicy policy) {
        return new PostgresReconciliationStore(jdbc, txManager, policy);
    }

    @Bean
    Reconciler reconciler(ReconciliationStore store, SettlementService settlement, ReconciliationPolicy policy,
                          ReconcilerProperties properties, Clock clock, MeterRegistry meterRegistry) {
        return new Reconciler(store, settlement, policy, properties, clock, meterRegistry);
    }
}
