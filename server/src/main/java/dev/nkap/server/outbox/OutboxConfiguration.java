package dev.nkap.server.outbox;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the pieces a delivery needs, whether it comes from the scheduled relay or a manual
 * replay: the claim/record store, the signing sender, the backoff policy, and a fallback
 * {@link Clock} if the reconciler's own didn't already supply one.
 *
 * <p><strong>Always on</strong> — unlike {@link OutboxRelaySchedulingConfiguration}. {@code
 * WebhookReplayController} calls {@link WebhookSender} and {@link OutboxRelayStore} directly,
 * and a replay must keep working even when {@code nkap.webhooks.enabled=false} has turned the
 * background relay off; "delivery is off" and "the API to resend one event by hand is off"
 * are different questions; see {@code docs/webhooks.md}.
 */
@Configuration
@EnableConfigurationProperties(OutboxRelayProperties.class)
class OutboxConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OutboxRelayPolicy outboxRelayPolicy(OutboxRelayProperties properties) {
        return new OutboxRelayPolicy(properties);
    }

    @Bean
    OutboxRelayStore outboxRelayStore(JdbcTemplate jdbc, PlatformTransactionManager txManager, OutboxRelayPolicy policy) {
        return new PostgresOutboxRelayStore(jdbc, txManager, policy);
    }

    @Bean
    WebhookSender webhookSender(OutboxRelayProperties properties, Clock clock) {
        return new WebhookSender(properties.requestTimeout(), clock);
    }
}
