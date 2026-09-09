package dev.nkap.server.payment;

import dev.nkap.core.payment.ReferenceId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * The reference {@link PaymentRepository}: a map. No persistence — a restart forgets
 * everything, which is exactly wrong for production and exactly why PostgreSQL is next.
 *
 * <p>It stores the live {@link Payment} object. That is safe here because a payment is
 * only ever mutated by the single caller that holds its idempotency claim.
 */
@Repository
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
}
