package dev.nkap.server.reconcile;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ReconciliationStore} in PostgreSQL: plain SQL through {@link JdbcTemplate}, the
 * claim wrapped in one {@link TransactionTemplate} so {@code FOR UPDATE SKIP LOCKED} and
 * the follow-up schedule write commit together.
 */
public final class PostgresReconciliationStore implements ReconciliationStore {

    private static final String CLAIM_DUE = """
            SELECT reference, provider, reconcile_attempts, unknown_since
              FROM payment
             WHERE state = 'UNKNOWN'
               AND escalated_at IS NULL
               AND reconcile_due_at IS NOT NULL
               AND reconcile_due_at <= ?
             ORDER BY reconcile_due_at
             FOR UPDATE SKIP LOCKED
             LIMIT ?
            """;

    private static final String ADVANCE_SCHEDULE =
            "UPDATE payment SET reconcile_attempts = ?, reconcile_due_at = ? WHERE reference = ?";

    private static final String ESCALATE = """
            UPDATE payment SET escalated_at = ?
             WHERE reference = ? AND state = 'UNKNOWN' AND escalated_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReconciliationPolicy policy;

    public PostgresReconciliationStore(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                       ReconciliationPolicy policy) {
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
                OffsetDateTime nextDue = OffsetDateTime.ofInstant(
                        now.plus(policy.intervalForAttempt(attempts)), ZoneOffset.UTC);
                jdbc.update(ADVANCE_SCHEDULE, attempts, nextDue, row.reference().value());
                claimed.add(new Claim(ProviderId.of(row.provider()), row.reference(), attempts, row.unknownSince()));
            }
            return claimed;
        });
    }

    @Override
    public boolean markEscalated(ReferenceId reference, Instant at) {
        Boolean escalated = tx.execute(status ->
                jdbc.update(ESCALATE, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), reference.value()) > 0);
        return Boolean.TRUE.equals(escalated);
    }

    private record Due(ReferenceId reference, String provider, int attempts, Instant unknownSince) {
    }

    private static final RowMapper<Due> DUE_MAPPER = (ResultSet rs, int rowNum) -> new Due(
            new ReferenceId(rs.getObject("reference", UUID.class)),
            rs.getString("provider"),
            rs.getInt("reconcile_attempts"),
            instantOrNull(rs.getObject("unknown_since", OffsetDateTime.class)));

    private static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
