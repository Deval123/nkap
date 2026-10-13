package dev.nkap.server.adjustment;

import dev.nkap.core.ledger.Ledger;
import dev.nkap.server.payment.PaymentRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wires the adjustment feature. The beans always exist; whether the endpoints answer is
 * {@link AdjustmentPolicy#enabled()}, checked by {@link AdjustmentService} on every call, so a
 * switched-off feature is a {@code 501} with its own problem type rather than a missing route.
 * The defaults are written inline, as the other directly-read properties' are, and
 * {@code application.yml} and {@code docs/configuration-reference.md} say the same.
 */
@Configuration
class AdjustmentConfiguration {

    @Bean
    AdjustmentPolicy adjustmentPolicy(
            @Value("${nkap.adjustments.enabled:false}") boolean enabled,
            @Value("${nkap.adjustments.max-amount-minor:100000}") long maxAmountMinor) {
        return new AdjustmentPolicy(enabled, maxAmountMinor);
    }

    @Bean
    AdjustmentStore adjustmentStore(JdbcTemplate jdbc, ObjectMapper json) {
        return new PostgresAdjustmentStore(jdbc, json);
    }

    @Bean
    AdjustmentService adjustmentService(AdjustmentPolicy policy, AdjustmentStore store, PaymentRepository payments,
                                        Ledger ledger, PlatformTransactionManager txManager, Clock clock) {
        return new AdjustmentService(policy, store, payments, ledger, txManager, clock);
    }
}
