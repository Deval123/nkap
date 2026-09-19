package dev.nkap.server.payment;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import java.time.Instant;
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
     * The payment {@code provider} submitted under the operator's own {@code providerReference}
     * — the mirror of {@link #findByReference}, resolving in the other direction, for a
     * callback that names only what the operator chose (issue #149, ADR 0011 §2). Scoped to
     * {@code provider}: a provider reference is only meaningful within one operator's own
     * namespace, and the caller (the callback endpoint) always has it before it has anything
     * else.
     *
     * <p><strong>Empty when zero payments match, and empty when more than one does — never a
     * guess between candidates.</strong> {@code provider_reference} carries no uniqueness
     * constraint, so nothing here assumes at most one row can ever match; picking one (the
     * newest, say) would decide which payment a real callback settles on no stronger basis
     * than which row happened to come back first. A caller that gets empty back cannot tell
     * "never recorded" from "recorded more than once" apart, and does not need to: neither
     * case is safe to act on, so both are refused the same way.
     *
     * @throws IllegalArgumentException if {@code providerReference} is blank — there would be
     *                                   nothing to resolve, and most rows carry exactly that
     *                                   blank default
     */
    Optional<Payment> findByProviderReference(ProviderId provider, String providerReference);

    /**
     * The payments the reconciler has given up retrying and flagged for a human: still
     * unresolved ({@code SUBMITTED}, {@code PENDING} or {@code UNKNOWN}), {@code escalated_at}
     * set, oldest escalation first. This is how an escalation is found without reading logs.
     */
    List<Payment> findEscalated();

    /**
     * Refund payments ({@code refund_of IS NOT NULL}) still in {@code CREATED}, created
     * before {@code olderThan} — a refund whose reservation committed but whose submit call
     * never ran, or never got the chance to record its outcome, because the process was
     * killed in between (issue #84). Deliberately restricted to refunds: an ordinary
     * {@code CREATED} collection or disbursement is left exactly as
     * {@link dev.nkap.core.payment.PaymentState#isUnresolved()} intends — still
     * {@code PaymentService}'s to own.
     */
    List<Payment> findStrandedRefunds(Instant olderThan);

    /**
     * Atomically increments the {@code SUCCEEDED} collection at {@code original}'s
     * {@code refunded_minor} by {@code amount}, in the database, under no lock this call
     * takes itself — the {@code UPDATE}'s own row-level atomicity is the actual guarantee
     * against two concurrent refunds together exceeding the original, backed by the
     * {@code CHECK} (V8) that refuses to let the result exceed the amount ever collected.
     *
     * <p>Throws {@link RefundExceedsRemainingException} when the database refuses it —
     * whether because this call alone would have exceeded the remaining balance, or because
     * it lost a race to a concurrent reservation that got there first. Any other integrity
     * error propagates unchanged.
     */
    void reserveRefund(ReferenceId original, Money amount);

    /**
     * The mirror of {@link #reserveRefund}: atomically decrements {@code original}'s
     * {@code refunded_minor} by {@code amount}, for a refund of it that ended {@code FAILED}
     * or {@code EXPIRED}.
     */
    void releaseRefundReservation(ReferenceId original, Money amount);
}
