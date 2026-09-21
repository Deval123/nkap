package dev.nkap.provider;

import java.util.Arrays;
import java.util.Set;

/**
 * Whether, and how, an adapter can resolve a payment whose submission got no answer, without a
 * human — {@link ProviderAdapter#submit} threw {@link ProviderUnavailableException}, there is
 * no {@link SubmitResult} to remember, only the reference Nkap chose before calling out.
 *
 * <p>Not "this adapter has a {@code query} method, or a {@code parseCallback} method" — every
 * adapter has both. The question a member here answers is narrower, and only about the one
 * moment a submission's own response never arrived at all (ADR 0014). An adapter author who
 * reads this as "which methods do I implement" will declare {@link #QUERY} because
 * {@link ProviderAdapter#query} exists on every adapter regardless of whether it can actually
 * answer for a lost submission — that misreading is exactly what {@link ProviderAdapter#resolves()}
 * exists to force a real answer to.
 */
public enum Resolution {

    /**
     * Polling {@link ProviderAdapter#query} with only the reference Nkap chose can bring back
     * an answer for a lost submission. True of an operator whose status call accepts the
     * caller's own idempotency key — MTN's {@code requesttopay} status endpoint does. Not true
     * of an operator whose only key for a lost submission is one it generated and returned in
     * the very response that was lost — M-Pesa's STK Push {@code CheckoutRequestID}, which
     * {@code stkpushquery} requires and which a caller who never received the submit response
     * never has.
     */
    QUERY,

    /**
     * The operator's own callback, once it can be attributed to the reference Nkap chose, can
     * bring back an answer for a lost submission even when nothing can be queried for it. Not
     * every adapter that receives callbacks may declare this: attribution needs a value Nkap
     * supplied that the callback carries back to it, which {@link ProviderAdapter#parseCallback}
     * alone cannot manufacture for an operator whose callback echoes none of Nkap's own values
     * (ADR 0008's amendment; issue #185).
     */
    CALLBACK;

    /**
     * The only way in this codebase to build the set {@link ProviderAdapter#resolves()}
     * returns — refuses an empty result, the same discipline {@link CallbackEvent} and
     * {@link PaymentIntent} already apply at construction to a value that would otherwise
     * silently mean "nothing usable here". An adapter that could resolve a lost submission
     * neither by query nor by callback would leave it settleable only by a person reading the
     * operator's own portal, which this gateway does not accept as an adapter.
     */
    public static Set<Resolution> of(Resolution... resolutions) {
        if (resolutions.length == 0) {
            throw new IllegalArgumentException(
                    "An adapter must declare at least one Resolution: with neither QUERY nor "
                            + "CALLBACK, a lost submission could never be resolved without a human");
        }
        return Set.copyOf(Arrays.asList(resolutions));
    }
}
