package dev.nkap.provider;

import dev.nkap.core.payment.PaymentState;

import java.util.Objects;

/**
 * What the provider said when the request was handed over.
 *
 * <p>An adapter returns {@link PaymentState#SUBMITTED} or {@link PaymentState#PENDING}
 * on acknowledgement, and throws {@link ProviderUnavailableException} when it does not
 * know — it never invents {@link PaymentState#FAILED} from silence.
 */
public record SubmitResult(PaymentState state, String providerReference, String rawResponse) {

    public SubmitResult {
        Objects.requireNonNull(state, "state");
        providerReference = providerReference == null ? "" : providerReference;
        rawResponse = rawResponse == null ? "" : rawResponse;
        if (state != PaymentState.SUBMITTED && state != PaymentState.PENDING) {
            throw new IllegalArgumentException(
                    "A submission may only report SUBMITTED or PENDING, not " + state
                            + ": a definitive outcome comes from query() or a callback");
        }
    }

    public static SubmitResult acknowledged(String providerReference, String rawResponse) {
        return new SubmitResult(PaymentState.SUBMITTED, providerReference, rawResponse);
    }
}
