package dev.nkap.server.payment;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final ReferenceId refundOf;
    private final List<PaymentTransition> history = new ArrayList<>();

    private PaymentState state;
    private Instant updatedAt;
    private String providerReference = "";
    private String providerTransactionId = "";

    // The installation's own base URL at the moment this payment was created — issue #122,
    // recorded so a simulated settlement and a real one stop being byte-identical in the
    // database. Known from configuration at birth, not learned from the operator the way
    // providerReference/providerTransactionId are, so it is set exactly once, immediately
    // after create()/createRefund() and before the first save() — persistence then makes it
    // truly immutable: a BEFORE UPDATE trigger on the payment table (V9) refuses any change
    // to this column once a row exists, regardless of what application code does afterwards.
    // Blank for a payment that predates that migration, or if routing genuinely could not
    // resolve one; never guessed.
    private String providerBaseUrl = "";

    // Meaningful only on a SUCCEEDED collection: the running total reserved or already paid
    // out against it by a refund (ADR 0010). Reserved the moment a refund is created, not
    // only once it settles, because an in-flight refund that later succeeds must not have
    // let a second refund spend the same money in the meantime. Released only if that refund
    // ends FAILED or EXPIRED; a SUCCEEDED refund, or one still unresolved, keeps its
    // reservation forever. Zero on every payment that has never had a refund taken against
    // it, which is every DISBURSE payment and every COLLECT payment nobody has refunded yet.
    //
    // This field is not the source of truth and PaymentRepository.save() does not persist
    // it (issue #84's first correction): the true value lives in the database column of the
    // same name, moved only by PaymentRepository.reserveRefund/releaseRefundReservation, each
    // a single atomic UPDATE. This field mirrors that value only for an object just rehydrated
    // from storage, or as a courtesy check's scratch pad (see reserveRefund) — never rely on
    // it reflecting a concurrent write this object's own methods did not make.
    private long refundedMinor;

    // The reconciler's schedule. Meaningful while the payment is unresolved — SUBMITTED,
    // PENDING or UNKNOWN. reconcileAttempts, reconcileDueAt and unresolvedSince are set once,
    // when the payment first enters that set, and thereafter advanced only by the reconciler:
    // the backoff and the escalation window both run from the first unresolved moment, not
    // from each hop between the three states (see applyTransition for why). escalatedAt is
    // stamped when the window is spent and a human is paged, and cleared only on that same
    // way in — a hop does not un-escalate a payment. A flag, not a state.
    private int reconcileAttempts;
    private Instant reconcileDueAt;
    private Instant escalatedAt;
    private Instant unresolvedSince;

    private Payment(ReferenceId reference, ProviderId provider, String merchantId, PaymentIntent intent,
                    ReferenceId refundOf, Instant now) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.merchantId = requireText(merchantId, "merchantId");
        this.intent = Objects.requireNonNull(intent, "intent");
        this.refundOf = refundOf;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.state = PaymentState.CREATED;
        this.updatedAt = now;
    }

    /** A new payment in {@link PaymentState#CREATED}, persisted before the operator is called. */
    public static Payment create(ReferenceId reference, ProviderId provider, String merchantId, PaymentIntent intent) {
        return new Payment(reference, provider, merchantId, intent, null, Instant.now());
    }

    /**
     * A refund: a {@code DISBURSE} payment like any other (ADR 0010 — not a third
     * {@code Capability.Operation}, and never {@link dev.nkap.core.ledger.LedgerEntry#reversalOf}),
     * distinguished only by {@code refundOf} naming the {@code SUCCEEDED} collection it sends
     * money back for. The caller ({@code RefundService}) has already reserved {@code intent}'s
     * amount against that collection's {@link #refundableRemaining()} before this is created.
     */
    public static Payment createRefund(ReferenceId reference, ProviderId provider, String merchantId,
                                       PaymentIntent intent, ReferenceId refundOf) {
        return new Payment(reference, provider, merchantId, intent, Objects.requireNonNull(refundOf, "refundOf"), Instant.now());
    }

    /**
     * Rebuilds a payment from storage. The only caller is a {@link PaymentRepository}
     * implementation reading rows back; nothing else assigns a state from outside, and this
     * does not run the state machine — the transitions it replays already happened and were
     * validated when they were first applied.
     */
    public static Payment rehydrate(ReferenceId reference, ProviderId provider, String merchantId, PaymentIntent intent,
                                    PaymentState state, String providerReference, String providerTransactionId,
                                    String providerBaseUrl, Instant createdAt, Instant updatedAt,
                                    List<PaymentTransition> history, int reconcileAttempts, Instant reconcileDueAt,
                                    Instant escalatedAt, Instant unresolvedSince, ReferenceId refundOf,
                                    long refundedMinor) {
        Payment payment = new Payment(reference, provider, merchantId, intent, refundOf, createdAt);
        payment.state = Objects.requireNonNull(state, "state");
        payment.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        payment.providerReference = providerReference == null ? "" : providerReference;
        payment.providerTransactionId = providerTransactionId == null ? "" : providerTransactionId;
        payment.providerBaseUrl = providerBaseUrl == null ? "" : providerBaseUrl;
        payment.history.addAll(history);
        payment.reconcileAttempts = reconcileAttempts;
        payment.reconcileDueAt = reconcileDueAt;
        payment.escalatedAt = escalatedAt;
        payment.unresolvedSince = unresolvedSince;
        payment.refundedMinor = refundedMinor;
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
        if (this.state.isUnresolved() && !previous.isUnresolved()) {
            // The payment has entered a fresh episode of being unresolved — from CREATED, or
            // from a state it had briefly resolved out of. Everything the reconciler tracks
            // starts here, and from here on is advanced only by the reconciler. A later hop
            // between SUBMITTED, PENDING and UNKNOWN restarts none of it:
            //   - the schedule (reconcileDueAt): due now, since there is nothing yet to
            //     preserve. A hop must NOT move it back to now — the claim that produced the
            //     hop already counted the attempt and pushed the next one out by a backoff
            //     interval, and discarding that collapses the re-query cadence to the pass
            //     interval (were an operator able to keep a payment hopping, roughly 2 880
            //     times a day at the thirty-second default — the load backoff exists to
            //     prevent). The attempt counter never drove the cadence; reconcileDueAt does.
            //   - the attempt count, and with it the backoff interval: the longer a payment
            //     has been unresolved the less sense a fast re-query of a struggling
            //     operator makes, so a hop does not pin it back to base.
            //   - the window start (unresolvedSince): one episode of not knowing gets one
            //     window, however many non-terminal states it passes through. An operator
            //     alternating two non-terminal answers would otherwise reset it every pass
            //     and the payment would never be escalated.
            //   - the escalation flag: cleared here, and only here. A hop leaves an escalated
            //     payment escalated — it is still that human's problem whichever non-terminal
            //     state it now wears — so the list a human reads does not flicker as the
            //     operator changes its answer. The flag lifts only when a genuinely new
            //     episode starts, which is the same moment a fresh window starts.
            this.reconcileAttempts = 0;
            this.reconcileDueAt = this.updatedAt;
            this.escalatedAt = null;
            this.unresolvedSince = this.updatedAt;
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

    /**
     * Records which installation base URL this payment was actually submitted to (issue
     * #122). Unlike {@link #recordProviderReference} and {@link #recordProviderTransactionId},
     * this is not learned progressively from the operator — it is known from configuration
     * the moment the payment is created, so the caller ({@link PaymentService},
     * {@link RefundService}) sets it exactly once, before the first {@code save()}. A blank
     * or {@code null} value leaves it blank, the same "not recorded" convention every other
     * provenance-shaped field on this class uses.
     */
    public void recordProviderBaseUrl(String value) {
        if (value != null && !value.isBlank()) {
            this.providerBaseUrl = value;
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

    /**
     * The installation base URL this payment was submitted to, or {@code ""} for a payment
     * that predates this being recorded at all (issue #122) — deliberately, not backfilled.
     */
    public String providerBaseUrl() {
        return providerBaseUrl;
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

    /** The collection this payment sends money back for, or empty for every payment that is not a refund. */
    public Optional<ReferenceId> refundOf() {
        return Optional.ofNullable(refundOf);
    }

    /** The running total reserved or already paid out by a refund against this payment. Always {@code 0} for a refund itself. */
    public long refundedMinor() {
        return refundedMinor;
    }

    /**
     * What is left of this ({@code SUCCEEDED} collection) payment for a new refund to claim:
     * the amount, less every reservation {@link #reserveRefund} has made against it so far.
     */
    public Money refundableRemaining() {
        Money amount = intent.amount();
        return amount.minus(Money.of(refundedMinor, amount.currency()));
    }

    /**
     * Checks {@code amount} against what this payment has left to refund, and — if it
     * fits — mutates this object's own {@code refundedMinor} to reflect the reservation.
     * Throws {@link RefundExceedsRemainingException} rather than let the running total pass
     * what was ever collected.
     *
     * <p><strong>This is a courtesy, not the rule.</strong> It used to be described as the
     * guard against two concurrent refunds together exceeding the original; it is not, and
     * making it one would require every caller to remember a lock this method cannot enforce
     * on their behalf. Call it on an unlocked, possibly stale snapshot — {@code
     * RefundController}'s own read, say — and it gives the common, non-racing case a precise
     * message before a single database write happens. It proves nothing about what a
     * concurrent refund is doing at the same moment, and this object's mutated field is never
     * itself persisted as the source of truth: {@code PaymentRepository.reserveRefund} is a
     * separate, atomic {@code UPDATE … SET refunded_minor = refunded_minor + ?} in the
     * database, refused at commit by the {@code CHECK} (V8) if it would exceed the amount
     * ever collected — that is what actually makes two concurrent refunds impossible, and it
     * is checked again, independently, no matter what this method already decided.
     */
    public void reserveRefund(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a refund amount must be positive, was " + amount);
        }
        Money updated = Money.of(refundedMinor, amount.currency()).plus(amount);
        if (updated.amount() > intent.amount().amount()) {
            throw new RefundExceedsRemainingException(reference, refundableRemaining(), amount);
        }
        refundedMinor = updated.amount();
    }

    /**
     * The in-memory mirror of {@link #reserveRefund}, kept for the same reason and subject
     * to the same caveat: this object's own {@code refundedMinor} is not what gets persisted.
     * {@code PaymentRepository.releaseRefundReservation} — a single atomic {@code UPDATE …
     * SET refunded_minor = refunded_minor - ?} — is. Meaningful for a refund that ended
     * {@code FAILED} or {@code EXPIRED}; never called for one that {@code SUCCEEDED} or is
     * still unresolved (including {@code UNKNOWN}) — an escalated refund keeps holding its
     * reservation, because un-reserving it on a guess would let the same money leave twice if
     * it later turns out to have gone through after all.
     */
    public void releaseRefundReservation(Money amount) {
        refundedMinor = Math.subtractExact(refundedMinor, amount.amount());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
