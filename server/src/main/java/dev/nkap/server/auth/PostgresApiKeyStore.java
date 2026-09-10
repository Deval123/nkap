package dev.nkap.server.auth;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link ApiKeyStore} in PostgreSQL. The {@code api_key} table (migration {@code V6}) holds
 * {@code token_sha256}, the merchant, the admin flag; never the token.
 *
 * <p>{@link #authenticate} is one indexed lookup on the hash plus a {@code last_used_at}
 * touch. The touch is best-effort — a row that vanished between the read and the update
 * (revoked mid-request) simply updates nothing.
 */
public final class PostgresApiKeyStore implements ApiKeyStore {

    private final JdbcTemplate jdbc;

    public PostgresApiKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiCredential> authenticate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        List<ApiCredential> found = jdbc.query(
                "SELECT id, merchant_id, is_admin FROM api_key WHERE token_sha256 = ?",
                CREDENTIAL_MAPPER, ApiKeys.hash(presentedToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiCredential credential = found.get(0);
        jdbc.update("UPDATE api_key SET last_used_at = ? WHERE id = ?",
                OffsetDateTime.now(ZoneOffset.UTC), credential.keyId());
        return Optional.of(credential);
    }

    @Override
    public Provisioned provision(String merchantId, boolean admin, String label) {
        return provisionWithToken(ApiKeys.newToken(), merchantId, admin, label);
    }

    @Override
    public Provisioned provisionWithToken(String token, String merchantId, boolean admin, String label) {
        Objects.requireNonNull(token, "token");
        String merchant = requireText(merchantId, "merchantId");
        String hash = ApiKeys.hash(token);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(
                "INSERT INTO api_key (id, token_sha256, merchant_id, is_admin, label) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (token_sha256) DO NOTHING",
                id, hash, merchant, admin, label == null ? "" : label);
        if (inserted == 1) {
            return new Provisioned(new ApiCredential(id, merchant, admin), token);
        }
        // Idempotent: the key already exists (a re-run of the demo's provisioning step).
        // Return it as it stands — the caller already holds the token.
        ApiCredential existing = jdbc.query(
                "SELECT id, merchant_id, is_admin FROM api_key WHERE token_sha256 = ?", CREDENTIAL_MAPPER, hash)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("api key vanished between insert and read"));
        return new Provisioned(existing, token);
    }

    private static final RowMapper<ApiCredential> CREDENTIAL_MAPPER = (ResultSet rs, int rowNum) -> new ApiCredential(
            rs.getObject("id", UUID.class),
            rs.getString("merchant_id"),
            rs.getBoolean("is_admin"));

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
