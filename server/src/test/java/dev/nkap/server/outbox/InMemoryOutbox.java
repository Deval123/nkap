package dev.nkap.server.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A list standing in for {@link Outbox} in the service unit tests, so those can stay fast
 * and Docker-free. The real one is {@link PostgresOutbox}; the transactional property this
 * class cannot exercise — that a failed write here rolls back a payment's state too — is
 * what {@code OutboxTransactionIT} proves against real PostgreSQL.
 */
public final class InMemoryOutbox implements Outbox {

    private final List<OutboxEvent> appended = new CopyOnWriteArrayList<>();

    @Override
    public void append(OutboxEvent event) {
        appended.add(event);
    }

    public List<OutboxEvent> events() {
        return List.copyOf(appended);
    }
}
