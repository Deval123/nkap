package dev.nkap.server.outbox;

import dev.nkap.server.webhook.WebhookEndpointStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background relay — {@code @Scheduled}, and the {@link OutboxRelay} bean it
 * runs on. Split from {@link OutboxConfiguration} on purpose: everything in that class is
 * needed by {@code WebhookReplayController} too, and must stay up even when this one is
 * switched off. {@code nkap.webhooks.enabled=false} turns off <strong>only</strong> the
 * scheduled pass — events keep queuing, signed and ready, and a manual replay still
 * delivers them; nothing claims a batch on its own until this is on again.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "nkap.webhooks", name = "enabled", matchIfMissing = true)
class OutboxRelaySchedulingConfiguration {

    @Bean
    OutboxRelay outboxRelay(OutboxRelayStore store, WebhookEndpointStore endpoints, WebhookSender sender,
                            OutboxRelayPolicy policy, OutboxRelayProperties properties, Clock clock) {
        return new OutboxRelay(store, endpoints, sender, policy, properties, clock);
    }
}
