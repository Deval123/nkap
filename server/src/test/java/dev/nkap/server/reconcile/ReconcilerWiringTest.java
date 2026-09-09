package dev.nkap.server.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.nkap.server.payment.SettlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The reconciler's Spring wiring, without a database: the context refresh binds
 * {@code nkap.reconciler.*}, parses {@code @Scheduled(fixedDelayString = ...)}, and builds
 * the {@link Reconciler} bean — and {@code enabled=false} removes the lot.
 */
class ReconcilerWiringTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ReconcilerConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(SettlementService.class, () -> mock(SettlementService.class))
            .withPropertyValues(
                    "nkap.reconciler.interval=30s",
                    "nkap.reconciler.batch-size=100",
                    "nkap.reconciler.backoff-base=1m",
                    "nkap.reconciler.backoff-max=1h",
                    "nkap.reconciler.window=24h");

    @Test
    @DisplayName("the reconciler, its store and policy are wired and the scheduled pass parses")
    void the_reconciler_is_wired() {
        context.run((AssertableApplicationContext ctx) -> assertThat(ctx)
                .hasNotFailed()
                .hasSingleBean(Reconciler.class)
                .hasSingleBean(ReconciliationStore.class)
                .hasSingleBean(ReconciliationPolicy.class));
    }

    @Test
    @DisplayName("nkap.reconciler.enabled=false removes the reconciler and its scheduler entirely")
    void the_enabled_flag_switches_it_off() {
        context.withPropertyValues("nkap.reconciler.enabled=false")
                .run((AssertableApplicationContext ctx) -> assertThat(ctx)
                        .hasNotFailed()
                        .doesNotHaveBean(Reconciler.class)
                        .doesNotHaveBean(ReconciliationStore.class));
    }
}
