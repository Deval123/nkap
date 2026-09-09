package dev.nkap.server.payment;

import dev.nkap.core.payment.ReferenceId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Serialises the read-decide-write for one payment reference.
 *
 * <p>Two callbacks for the same reference can arrive together; so can a callback and the
 * still-running submit that created the payment. Both would otherwise mutate the same
 * {@link Payment} — which is not thread-safe — and both might try to settle. Every stretch
 * of code that reads a payment, decides a transition and writes it back runs inside
 * {@link #run}, keyed by the reference, so only one does at a time.
 *
 * <p><strong>PostgreSQL replaces this.</strong> The database serialises on
 * {@code SELECT … FOR UPDATE} of the payment row, inside the transaction that does the
 * read-decide-write: there is no lock map to keep and no process-local scope to escape.
 * The map here is never evicted — the same limitation as {@link InMemoryPaymentRepository},
 * and it goes away with that one.
 */
@Component
public class ReferenceLocks {

    private final ConcurrentMap<ReferenceId, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Runs {@code action} with the lock for {@code reference} held. */
    public void run(ReferenceId reference, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(reference, r -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
