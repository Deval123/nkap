package dev.nkap.server.webhook;

import java.util.Optional;

/**
 * Where a merchant's webhook endpoint lives. One endpoint per merchant in this slice —
 * several endpoints for one merchant, and per-event subscriptions, are a later slice.
 *
 * <p>Mirrors {@code ApiKeyStore}'s shape — {@link #find} on every delivery, {@link #provision}
 * only from the host-side command — but not its secrecy: {@link #find} returns the secret in
 * the open, because {@link dev.nkap.server.outbox.WebhookSigner} has to use it, not merely
 * check it. See {@link WebhookEndpoint}.
 */
public interface WebhookEndpointStore {

    /** The endpoint registered for {@code merchantId}, or empty if none is. */
    Optional<WebhookEndpoint> find(String merchantId);

    /**
     * Registers (or replaces) {@code merchantId}'s endpoint at {@code url}, with a freshly
     * generated secret. Only ever called by the host-side provisioning command, never a route.
     */
    WebhookEndpoint provision(String merchantId, String url);

    /**
     * Registers (or replaces) {@code merchantId}'s endpoint with a secret chosen by the
     * caller, for the demo and for tests that need a known value.
     */
    WebhookEndpoint provisionWithSecret(String secret, String merchantId, String url);
}
