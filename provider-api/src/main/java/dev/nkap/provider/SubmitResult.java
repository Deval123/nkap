package dev.nkap.provider;

import dev.nkap.core.payment.PaymentState;

import java.util.Objects;

/**
 * What the provider said when the request was handed over. There are three outcomes, not
 * two, and a caller must handle all of them — which is why this is a sealed hierarchy
 * rather than a record with a nullable field. It is the same shape, and for the same
 * reason, as {@code IdempotentOutcome} in {@code core}.
 *
 * <ul>
 *   <li>{@link Acknowledged} — the provider took the request; nothing has settled.</li>
 *   <li>{@link Rejected} — the provider refused it outright; no payment exists.</li>
 *   <li>a call that did not answer is not a {@code SubmitResult} at all: the adapter
 *       throws {@link ProviderUnavailableException}, which means "I do not know".</li>
 * </ul>
 *
 * <p>See ADR 0005. The contract first said a submission could only report {@code SUBMITTED}
 * or {@code PENDING}; implementing MTN showed a {@code 400} is a definitive outcome
 * available at submission, and it is neither of those nor an unknown.
 */
public sealed interface SubmitResult {

    /**
     * The provider accepted the request. Nothing has settled yet — the outcome comes from
     * {@code query()} or a callback.
     *
     * @param state             {@link PaymentState#SUBMITTED} or {@link PaymentState#PENDING};
     *                          never a terminal state, because a submission never is one
     * @param providerReference the provider's own id for the request, when it returns one
     * @param rawResponse       the response body, verbatim, for the record
     */
    record Acknowledged(PaymentState state, String providerReference, String rawResponse)
            implements SubmitResult {

        public Acknowledged {
            Objects.requireNonNull(state, "state");
            providerReference = providerReference == null ? "" : providerReference;
            rawResponse = rawResponse == null ? "" : rawResponse;
            if (state != PaymentState.SUBMITTED && state != PaymentState.PENDING) {
                throw new IllegalArgumentException(
                        "An acknowledgement may only report SUBMITTED or PENDING, not " + state
                                + ": a definitive outcome comes from query() or a callback");
            }
        }
    }

    /**
     * The provider refused the request outright: it was invalid, and no payment exists or
     * will exist under this reference.
     *
     * <p>It carries no {@link PaymentState}. A rejection always means {@code FAILED}, and
     * letting an adapter choose the state would allow nonsense. The gateway records the
     * transition {@code CREATED → FAILED} with {@code providerCode} and reports it to the
     * merchant.
     *
     * @param providerCode the operator's machine-readable code, e.g. {@code INVALID_CURRENCY}
     * @param reason       the operator's human-readable message, for the record only
     * @param rawResponse  the response body, verbatim
     */
    record Rejected(String providerCode, String reason, String rawResponse) implements SubmitResult {

        public Rejected {
            providerCode = providerCode == null ? "" : providerCode;
            reason = reason == null ? "" : reason;
            rawResponse = rawResponse == null ? "" : rawResponse;
        }
    }

    /**
     * An {@link Acknowledged} result in {@link PaymentState#SUBMITTED}. Kept so call sites
     * that only ever acknowledge read exactly as they did before.
     */
    static SubmitResult acknowledged(String providerReference, String rawResponse) {
        return new Acknowledged(PaymentState.SUBMITTED, providerReference, rawResponse);
    }
}
