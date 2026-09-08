package dev.nkap.server.payment;

import dev.nkap.core.payment.ReferenceId;
import java.util.Optional;

/**
 * Where payments are kept.
 *
 * <p>An interface because PostgreSQL is the next block and this is the seam it plugs into.
 * The in-memory implementation is a step, not a mode: there is no flag that selects it.
 */
public interface PaymentRepository {

    /** Persists the payment's current state. Overwrites any earlier snapshot of the same reference. */
    void save(Payment payment);

    Optional<Payment> findByReference(ReferenceId reference);
}
