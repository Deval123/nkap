package dev.nkap.server.payment;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import java.time.Instant;
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
    public Optional<Payment> findByProviderReference(ProviderId provider, String providerReference) {
        if (providerReference == null || providerReference.isBlank()) {
            throw new IllegalArgumentException("providerReference must not be blank");
        }
        // Matches PostgresPaymentRepository's own rule: exactly one match resolves; zero or
        // more than one both return empty, never a guess among candidates.
        List<Payment> matches = byReference.values().stream()
                .filter(payment -> payment.provider().equals(provider) && providerReference.equals(payment.providerReference()))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    @Override
    public List<Payment> findEscalated() {
        return byReference.values().stream()
                .filter(payment -> payment.escalatedAt() != null && payment.state().isUnresolved())
                .sorted(Comparator.comparing(Payment::escalatedAt))
                .toList();
    }

    @Override
    public List<Payment> findStrandedRefunds(Instant olderThan) {
        return byReference.values().stream()
                .filter(payment -> payment.refundOf().isPresent() && payment.state() == PaymentState.CREATED
                        && payment.createdAt().isBefore(olderThan))
                .sorted(Comparator.comparing(Payment::createdAt))
                .toList();
    }

    @Override
    public void reserveRefund(ReferenceId original, Money amount) {
        // No concurrent writer to race here (this double is single-writer by construction —
        // see the class javadoc), so the domain method's own check is the whole
        // implementation; the real guarantee under concurrency is PostgresPaymentRepository's
        // atomic UPDATE, proved against a real database.
        findByReference(original).orElseThrow(() -> new IllegalStateException("no payment for reference " + original))
                .reserveRefund(amount);
    }

    @Override
    public void releaseRefundReservation(ReferenceId original, Money amount) {
        findByReference(original).orElseThrow(() -> new IllegalStateException("no payment for reference " + original))
                .releaseRefundReservation(amount);
    }
}
