package dev.nkap.server.webhook;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Where one merchant receives its webhooks, and the secret {@link dev.nkap.server.outbox.WebhookSigner}
 * signs them with.
 *
 * <p>Unlike {@link dev.nkap.server.auth.ApiCredential}, this record carries the secret in
 * the open. An API key is hashed and only ever compared; a signing secret has to be
 * <strong>used</strong> — HMAC-SHA256 needs the actual bytes on every delivery — so the store
 * behind this class keeps it readable, and this record is allowed to say so. Whoever can
 * read the {@code webhook_endpoint} table can forge a notification to this merchant; that
 * consequence is written down in {@code docs/positioning.md}, not just here.
 */
public record WebhookEndpoint(UUID id, String merchantId, String url, String secret, Instant createdAt) {

    public WebhookEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
