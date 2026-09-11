package dev.nkap.server.outbox;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link Outbox} in PostgreSQL: one {@code INSERT} into {@code outbox_event} (migration
 * {@code V7}), due for delivery immediately.
 *
 * <p>{@link TransactionTemplate}'s default propagation joins whatever transaction is
 * already open rather than nesting one inside it — the same reasoning as
 * {@code PostgresLedger.append}, and for the same reason: the caller ({@code SettlementService},
 * {@code PaymentService}) already has one, and the whole point of this class is to write
 * inside it, not beside it.
 */
public final class PostgresOutbox implements Outbox {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresOutbox(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void append(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        OffsetDateTime dueNow = OffsetDateTime.now(ZoneOffset.UTC);
        tx.executeWithoutResult(status -> jdbc.update(
                "INSERT INTO outbox_event (id, merchant_id, event_type, payload, next_attempt_at) VALUES (?, ?, ?, ?, ?)",
                event.id(), event.merchantId(), event.eventType(), event.payload(), dueNow));
    }
}
