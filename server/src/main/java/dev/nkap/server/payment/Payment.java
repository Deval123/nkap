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

    // The reconciler's schedule. Meaningful while the payment is unresolved — SUBMITTED,
    // PENDING or UNKNOWN. reconcileAttempts and unresolvedSince are set once, when the
    // payment leaves CREATED for that set, and thereafter advanced only by the reconciler:
    // the backoff and the escalation window both run from the first unresolved moment, not
    // from each hop between the three states (see applyTransition for why). A hop still
    // makes the payment due now and clears any escalation. escalatedAt is stamped when the
    // window is spent and a human is paged — a flag, not a state.
    private int reconcileAttempts;
    private Instant reconcileDueAt;
    private Instant escalatedAt;
    private Instant unresolvedSince;

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
                                    int reconcileAttempts, Instant reconcileDueAt, Instant escalatedAt,
                                    Instant unresolvedSince) {
        Payment payment = new Payment(reference, provider, merchantId, intent, createdAt);
        payment.state = Objects.requireNonNull(state, "state");
        payment.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        payment.providerReference = providerReference == null ? "" : providerReference;
        payment.providerTransactionId = providerTransactionId == null ? "" : providerTransactionId;
        payment.history.addAll(history);
        payment.reconcileAttempts = reconcileAttempts;
        payment.reconcileDueAt = reconcileDueAt;
        payment.escalatedAt = escalatedAt;
        payment.unresolvedSince = unresolvedSince;
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
        if (this.state.isUnresolved()) {
            // Entering, or hopping within, the states the reconciler chases. A state change
            // is news, so make the payment due to be looked at now; clear any escalation,
            // since a payment that has started moving again may not need a human after all.
            this.reconcileDueAt = this.updatedAt;
            this.escalatedAt = null;
            if (!previous.isUnresolved()) {
                // The window start and the attempt count are set once, on the way in from
                // CREATED, and thereafter advanced only by the reconciler. A hop between
                // SUBMITTED, PENDING and UNKNOWN restarts neither:
                //   - the window, because an operator alternating two non-terminal answers
                //     would otherwise reset it every pass and the payment would never be
                //     escalated;
                //   - the attempt count, and with it the backoff, because the longer a
                //     payment has been unresolved the less it makes sense to re-query an
                //     operator that is plainly struggling. Reset on every hop, the backoff
                //     pins near its base — roughly 1 440 queries for one ping-ponging
                //     payment over a one-day window at the one-minute default — which is
                //     the load backoff exists to prevent, aimed at the operator least able
                //     to take it.
                this.reconcileAttempts = 0;
                this.unresolvedSince = this.updatedAt;
            }
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

    /**
     * When this payment became unresolved — left {@code CREATED} for a non-terminal state —
     * which is the instant the escalation window is measured from. Unchanged by later hops
     * between {@code SUBMITTED}, {@code PENDING} and {@code UNKNOWN}. {@code null} for a
     * payment that never left {@code CREATED} or went straight to a terminal state.
     */
    public Instant unresolvedSince() {
        return unresolvedSince;
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
