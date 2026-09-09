package dev.nkap.server.payment;

import dev.nkap.core.payment.ReferenceId;
import java.util.List;
import java.util.Optional;

/**
 * Where payments are kept.
 *
 * <p>The production implementation is {@code PostgresPaymentRepository}; an in-memory one
 * lives in the test tree as a fast double for the service unit tests. There is no flag and
 * no in-memory mode — {@code StoresConfiguration} builds the PostgreSQL one and nothing
 * else does.
 */
public interface PaymentRepository {

    /** Persists the payment: the row, and any history rows not yet stored. Idempotent. */
    void save(Payment payment);

    /** The payment for {@code reference}, without taking a lock — for reads that only display it. */
    Optional<Payment> findByReference(ReferenceId reference);

    /**
     * The payment for {@code reference}, taking its row for the duration of the current
     * transaction so a concurrent read-decide-write for the same reference waits. This is
     * the serialisation the callback path and the submit path share; outside a transaction
     * it behaves like {@link #findByReference}. The in-memory double, single-writer by
     * construction, does not lock.
     */
    Optional<Payment> findByReferenceForUpdate(ReferenceId reference);

    /**
     * The payments the reconciler has given up retrying and flagged for a human: still
     * unresolved ({@code SUBMITTED}, {@code PENDING} or {@code UNKNOWN}), {@code escalated_at}
     * set, oldest escalation first. This is how an escalation is found without reading logs.
     */
    List<Payment> findEscalated();
}
