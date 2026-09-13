package dev.nkap.server.webhook;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A map standing in for {@link WebhookEndpointStore} in the service unit tests. Empty by
 * default: a test that never registers an endpoint gets the same "nothing to notify"
 * behaviour {@link dev.nkap.server.outbox.OutboxNotifier} gives a merchant with none in
 * production.
 */
public final class InMemoryWebhookEndpointStore implements WebhookEndpointStore {

    private final Map<String, WebhookEndpoint> byMerchant = new ConcurrentHashMap<>();

    @Override
    public Optional<WebhookEndpoint> find(String merchantId) {
        return Optional.ofNullable(byMerchant.get(merchantId));
    }

    @Override
    public WebhookEndpoint provision(String merchantId, String url) {
        return provisionWithSecret("whsec_test-" + UUID.randomUUID(), merchantId, url);
    }

    @Override
    public WebhookEndpoint provisionWithSecret(String secret, String merchantId, String url) {
        WebhookEndpoint endpoint = new WebhookEndpoint(UUID.randomUUID(), merchantId, url, secret, Instant.now());
        byMerchant.put(merchantId, endpoint);
        return endpoint;
    }
}
