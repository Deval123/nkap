package dev.nkap.server.persistence;

import dev.nkap.core.idempotency.IdempotencyKey;
import dev.nkap.core.idempotency.IdempotencyStore;
import dev.nkap.core.idempotency.IdempotentOutcome;
import dev.nkap.core.idempotency.RequestFingerprint;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link IdempotencyStore} in PostgreSQL.
 *
 * <p>{@code begin} stays atomic the way the contract requires: a single
 * {@code INSERT … ON CONFLICT DO NOTHING} decides who gets {@link IdempotentOutcome.Proceed}
 * — the row it inserts, or does not. It never reads then writes. The follow-up
 * {@code SELECT} only classifies a claim that already existed, into the other three
 * outcomes.
 */
public final class PostgresIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public PostgresIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IdempotentOutcome begin(IdempotencyKey key, RequestFingerprint fingerprint) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");

        // A concurrent abandon() between the failed insert and the classifying select can
        // remove the row; re-run in that case. Bounded, because abandon() only ever removes
        // an unanswered claim once.
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Integer> inserted = jdbc.query(
                    "INSERT INTO idempotency_record (merchant_id, idempotency_key, fingerprint_sha256) "
                            + "VALUES (?, ?, ?) ON CONFLICT (merchant_id, idempotency_key) DO NOTHING RETURNING 1",
                    (rs, row) -> 1,
                    key.merchantId(), key.key(), fingerprint.sha256());
            if (!inserted.isEmpty()) {
                return new IdempotentOutcome.Proceed(key);
            }
            try {
                return jdbc.queryForObject(
                        "SELECT fingerprint_sha256, response FROM idempotency_record "
                                + "WHERE merchant_id = ? AND idempotency_key = ?",
                        (rs, row) -> classify(key, fingerprint, rs.getString("fingerprint_sha256"), rs.getString("response")),
                        key.merchantId(), key.key());
            } catch (EmptyResultDataAccessException raced) {
                // The claim was abandoned under us. Try to take it again.
            }
        }
        throw new IllegalStateException("begin kept racing an abandon for key " + key);
    }

    private static IdempotentOutcome classify(IdempotencyKey key, RequestFingerprint fingerprint,
                                              String storedFingerprint, String response) {
        if (!storedFingerprint.equals(fingerprint.sha256())) {
            return new IdempotentOutcome.Conflict(key);
        }
        if (response == null) {
            return new IdempotentOutcome.InProgress(key);
        }
        return new IdempotentOutcome.Replay(key, response);
    }

    @Override
    public void complete(IdempotencyKey key, String response) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(response, "response");
        jdbc.update(
                "UPDATE idempotency_record SET response = ?, completed_at = now() "
                        + "WHERE merchant_id = ? AND idempotency_key = ?",
                response, key.merchantId(), key.key());
    }

    @Override
    public void abandon(IdempotencyKey key) {
        Objects.requireNonNull(key, "key");
        jdbc.update(
                "DELETE FROM idempotency_record "
                        + "WHERE merchant_id = ? AND idempotency_key = ? AND response IS NULL",
                key.merchantId(), key.key());
    }
}
