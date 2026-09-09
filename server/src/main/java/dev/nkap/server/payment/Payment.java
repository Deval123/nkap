package dev.nkap.server.payment;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A payment: the thing this project is about.
 *
 * <p>It is born in {@link PaymentState#CREATED} — a birth, not a transition, so it has no
 * history entry — and every subsequent move goes through {@link PaymentState#transitionTo},
 * which is the only place the machine is decided. Nothing outside this class assigns a
 * state, and no state is added here that the machine does not know.
 *
 * <p>Not thread-safe, and it does not need to be: every read-decide-write for one
 * reference is serialised on the payment's row with {@code SELECT … FOR UPDATE}, inside
 * the transaction that persists the result, so two callers never hold the same payment at
 * once.
 */
public final class Payment {

    private final ReferenceId reference;
    private final ProviderId provider;
    private final String merchantId;
    private final PaymentIntent intent;
    private final Instant createdAt;
    private final List<PaymentTransition> history = new ArrayList<>();

    private PaymentState state;
    private Instant updatedAt;
    private String providerReference = "";
    private String providerTransactionId = "";

    // The reconciler's schedule. Meaningful only while the payment is UNKNOWN: a payment
    // that becomes UNKNOWN is due for reconciliation immediately (reconcileDueAt = now,
    // attempts = 0), and any earlier escalation is cleared so a payment that cycled back
    // through UNKNOWN is picked up again. The reconciler advances attempts and reconcileDueAt
    // itself; escalatedAt is stamped when the retry window is spent and a human is paged —
    // it is a flag, not a state, and the payment stays UNKNOWN.
    private int reconcileAttempts;
    private Instant reconcileDueAt;
    private Instant escalatedAt;

    private Payment(ReferenceId reference, ProviderId provider, String merchantId, PaymentIntent intent, Instant now) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.merchantId = requireText(merchantId, "merchantId");
        this.intent = Objects.requireNonNull(intent, "intent");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.state = PaymentState.CREATED;
        this.updatedAt = now;
    }

    /** A new payment in {@link PaymentState#CREATED}, persisted before the operator is called. */
    public static Payment create(ReferenceId reference, ProviderId provider, String merchantId, PaymentIntent intent) {
        return new Payment(reference, provider, merchantId, intent, Instant.now());
    }

    /**
     * Rebuilds a payment from storage. The only caller is a {@link PaymentRepository}
     * implementation reading rows back; nothing else assigns a state from outside, and this
     * does not run the state machine — the transitions it replays already happened and were
     * validated when they were first applied.
     */
    public static Payment rehydrate(ReferenceId reference, ProviderId provider, String merchantId, PaymentIntent intent,
                                    PaymentState state, String providerReference, String providerTransactionId,
                                    Instant createdAt, Instant updatedAt, List<PaymentTransition> history,
                                    int reconcileAttempts, Instant reconcileDueAt, Instant escalatedAt) {
        Payment payment = new Payment(reference, provider, merchantId, intent, createdAt);
        payment.state = Objects.requireNonNull(state, "state");
        payment.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        payment.providerReference = providerReference == null ? "" : providerReference;
        payment.providerTransactionId = providerTransactionId == null ? "" : providerTransactionId;
        payment.history.addAll(history);
        payment.reconcileAttempts = reconcileAttempts;
        payment.reconcileDueAt = reconcileDueAt;
        payment.escalatedAt = escalatedAt;
        return payment;
    }

    /**
     * Moves the payment to {@code target}, recording why. {@code target} is validated by
     * {@link PaymentState#transitionTo}; an illegal move throws rather than being ignored.
     */
    public void applyTransition(PaymentState target, PaymentTransition.Cause cause, String operatorCode,
                                String note, String rawResponse) {
        PaymentState previous = this.state;
        this.state = previous.transitionTo(target);
        this.updatedAt = Instant.now();
        if (this.state == PaymentState.UNKNOWN) {
            // A payment that just became UNKNOWN is due for the reconciler now, on a clean
            // schedule. Clearing escalatedAt re-arms a payment that had been escalated and
            // then cycled back through UNKNOWN.
            this.reconcileAttempts = 0;
            this.reconcileDueAt = this.updatedAt;
            this.escalatedAt = null;
        }
        this.history.add(new PaymentTransition(previous, this.state, this.updatedAt, cause, operatorCode, note, rawResponse));
    }

    /** Records the operator's own reference for the request, once it is known. */
    public void recordProviderReference(String value) {
        if (value != null && !value.isBlank()) {
            this.providerReference = value;
        }
    }

    /** Records the operator's transaction id for the settled movement, once it is known. */
    public void recordProviderTransactionId(String value) {
        if (value != null && !value.isBlank()) {
            this.providerTransactionId = value;
        }
    }

    public ReferenceId reference() {
        return reference;
    }

    public ProviderId provider() {
        return provider;
    }

    public String merchantId() {
        return merchantId;
    }

    public PaymentIntent intent() {
        return intent;
    }

    public PaymentState state() {
        return state;
    }

    public String providerReference() {
        return providerReference;
    }

    public String providerTransactionId() {
        return providerTransactionId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** How many times the reconciler has queried the operator about this payment. */
    public int reconcileAttempts() {
        return reconcileAttempts;
    }

    /** When the reconciler's next attempt is due, or {@code null} if it never entered UNKNOWN. */
    public Instant reconcileDueAt() {
        return reconcileDueAt;
    }

    /** When this payment was escalated to a human, or {@code null} if it has not been. */
    public Instant escalatedAt() {
        return escalatedAt;
    }

    /** The transitions so far, oldest first. Unmodifiable. */
    public List<PaymentTransition> history() {
        return List.copyOf(history);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
