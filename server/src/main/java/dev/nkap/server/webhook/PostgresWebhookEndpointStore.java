package dev.nkap.server.webhook;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link WebhookEndpointStore} in PostgreSQL. The {@code webhook_endpoint} table (migration
 * {@code V7}) holds one row per merchant — {@code merchant_id} is {@code UNIQUE} — so
 * provisioning is an upsert: re-running the command rotates the endpoint rather than
 * failing.
 */
public final class PostgresWebhookEndpointStore implements WebhookEndpointStore {

    private final JdbcTemplate jdbc;

    public PostgresWebhookEndpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WebhookEndpoint> find(String merchantId) {
        List<WebhookEndpoint> found = jdbc.query(
                "SELECT id, merchant_id, url, secret, created_at FROM webhook_endpoint WHERE merchant_id = ?",
                ENDPOINT_MAPPER, merchantId);
        return found.stream().findFirst();
    }

    @Override
    public WebhookEndpoint provision(String merchantId, String url) {
        return provisionWithSecret(WebhookSecrets.newSecret(), merchantId, url);
    }

    @Override
    public WebhookEndpoint provisionWithSecret(String secret, String merchantId, String url) {
        Objects.requireNonNull(secret, "secret");
        String merchant = requireText(merchantId, "merchantId");
        String endpointUrl = requireText(url, "url");
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO webhook_endpoint (id, merchant_id, url, secret, created_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (merchant_id) DO UPDATE SET url = EXCLUDED.url, secret = EXCLUDED.secret",
                id, merchant, endpointUrl, secret, createdAt);
        return find(merchant).orElseThrow(() -> new IllegalStateException("webhook endpoint vanished right after being written"));
    }

    private static final RowMapper<WebhookEndpoint> ENDPOINT_MAPPER = (ResultSet rs, int rowNum) -> new WebhookEndpoint(
            rs.getObject("id", UUID.class),
            rs.getString("merchant_id"),
            rs.getString("url"),
            rs.getString("secret"),
            instant(rs.getObject("created_at", OffsetDateTime.class)));

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
