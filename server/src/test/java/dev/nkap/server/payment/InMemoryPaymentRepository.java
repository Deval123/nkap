package dev.nkap.server.payment;

import dev.nkap.core.payment.ReferenceId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A map standing in for {@link PaymentRepository} in the service unit tests, so those can
 * stay fast and Docker-free. The real one is {@code PostgresPaymentRepository}; the
 * behaviour these tests defend is exercised for real by {@code PaymentPersistenceIT}.
 *
 * <p>It stores the live {@link Payment} object, which is safe here because the unit tests
 * are single-threaded. It does not lock in {@link #findByReferenceForUpdate}: there is no
 * concurrent writer to serialise against.
 */
public final class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<ReferenceId, Payment> byReference = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        byReference.put(payment.reference(), payment);
    }

    @Override
    public Optional<Payment> findByReference(ReferenceId reference) {
        return Optional.ofNullable(byReference.get(reference));
    }

    @Override
    public Optional<Payment> findByReferenceForUpdate(ReferenceId reference) {
        return findByReference(reference);
    }

    @Override
    public List<Payment> findEscalated() {
        return byReference.values().stream()
                .filter(payment -> payment.escalatedAt() != null && payment.state().isUnresolved())
                .sorted(Comparator.comparing(Payment::escalatedAt))
                .toList();
    }
}
