package dev.nkap.server.outbox;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link OutboxRelayStore} in PostgreSQL: plain SQL through {@link JdbcTemplate}, the claim
 * wrapped in one {@link TransactionTemplate} so {@code FOR UPDATE SKIP LOCKED} and the
 * follow-up schedule write commit together — the same shape as
 * {@code PostgresReconciliationStore}.
 */
public final class PostgresOutboxRelayStore implements OutboxRelayStore {

    private static final String CLAIM_DUE = """
            SELECT id, merchant_id, event_type, payload, attempts
              FROM outbox_event
             WHERE delivered_at IS NULL
               AND dead_lettered_at IS NULL
               AND next_attempt_at <= ?
             ORDER BY next_attempt_at
             FOR UPDATE SKIP LOCKED
             LIMIT ?
            """;

    private static final String ADVANCE_SCHEDULE =
            "UPDATE outbox_event SET attempts = ?, next_attempt_at = ? WHERE id = ?";

    private static final String MARK_DELIVERED =
            "UPDATE outbox_event SET delivered_at = ? WHERE id = ?";

    private static final String RECORD_FAILURE =
            "UPDATE outbox_event SET last_error = ? WHERE id = ?";

    private static final String RECORD_FAILURE_DEAD_LETTER =
            "UPDATE outbox_event SET last_error = ?, dead_lettered_at = ? WHERE id = ?";

    private static final String FIND_BY_ID =
            "SELECT id, merchant_id, event_type, payload FROM outbox_event WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxRelayPolicy policy;

    public PostgresOutboxRelayStore(JdbcTemplate jdbc, PlatformTransactionManager txManager, OutboxRelayPolicy policy) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.policy = policy;
    }

    @Override
    public List<Claim> claimDue(int batch, Instant now) {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return tx.execute(status -> {
            List<Due> due = jdbc.query(CLAIM_DUE, DUE_MAPPER, cutoff, batch);
            List<Claim> claimed = new ArrayList<>(due.size());
            for (Due row : due) {
                int attempts = row.attempts() + 1;
                OffsetDateTime nextAttempt = OffsetDateTime.ofInstant(
                        now.plus(policy.intervalForAttempt(attempts)), ZoneOffset.UTC);
                jdbc.update(ADVANCE_SCHEDULE, attempts, nextAttempt, row.id());
                claimed.add(new Claim(row.id(), row.merchantId(), row.eventType(), row.payload(), attempts));
            }
            return claimed;
        });
    }

    @Override
    public void markDelivered(UUID id, Instant at) {
        jdbc.update(MARK_DELIVERED, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id);
    }

    @Override
    public void recordFailure(UUID id, String error, boolean deadLetter, Instant at) {
        if (deadLetter) {
            jdbc.update(RECORD_FAILURE_DEAD_LETTER, error, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id);
        } else {
            jdbc.update(RECORD_FAILURE, error, id);
        }
    }

    @Override
    public Optional<StoredEvent> find(UUID id) {
        List<StoredEvent> found = jdbc.query(FIND_BY_ID, STORED_EVENT_MAPPER, id);
        return found.stream().findFirst();
    }

    private static final RowMapper<StoredEvent> STORED_EVENT_MAPPER = (ResultSet rs, int rowNum) -> new StoredEvent(
            rs.getObject("id", UUID.class),
            rs.getString("merchant_id"),
            rs.getString("event_type"),
            rs.getString("payload"));

    private record Due(UUID id, String merchantId, String eventType, String payload, int attempts) {
    }

    private static final RowMapper<Due> DUE_MAPPER = (ResultSet rs, int rowNum) -> new Due(
            rs.getObject("id", UUID.class),
            rs.getString("merchant_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getInt("attempts"));
}
