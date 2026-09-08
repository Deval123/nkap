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
 * <p>Not thread-safe, and it does not need to be in this slice: the idempotency store
 * hands exactly one caller {@code Proceed} for a given key, so two requests never mutate
 * the same payment at once.
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
     * Moves the payment to {@code target}, recording why. {@code target} is validated by
     * {@link PaymentState#transitionTo}; an illegal move throws rather than being ignored.
     */
    public void applyTransition(PaymentState target, PaymentTransition.Cause cause, String operatorCode,
                                String note, String rawResponse) {
        PaymentState previous = this.state;
        this.state = previous.transitionTo(target);
        this.updatedAt = Instant.now();
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
