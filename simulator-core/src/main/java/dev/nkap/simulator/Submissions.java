package dev.nkap.simulator;

import dev.nkap.simulator.scenario.CallbackStep;
import dev.nkap.simulator.scenario.Timeline;
import dev.nkap.simulator.scenario.TimelineEngine;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What happens when a payment is submitted, before its face says anything about it:
 * the payment is given its identity, recorded, resolved to a scenario, and its callbacks
 * are scheduled. A face parses the request before calling this and renders the outcome
 * after; everything in between is decided here, the same way for every operator.
 *
 * <p>The order is deliberate. The payment is recorded before its scenario is consulted, so
 * a refused repeat never resolves one. Callbacks are scheduled when the scenario resolves —
 * before the face applies any submit delay — which is the only reason a callback declared
 * with {@code after: PT0S} can reach the client while its submission is still waiting on
 * that delay: issue #6, the callback that arrives before the submit response. A face must
 * therefore call this first and delay second.
 *
 * @param <T> the face's scenario
 * @param <S> the face's status
 */
@Component
public class Submissions<T extends Timeline<?, ? extends CallbackStep<S>>, S> {

    /**
     * What a submission came to: the identity the payment was recorded under and the
     * scenario it resolved to — or, for a repeat the operator refuses, no scenario at all.
     */
    public record Submission<T>(String paymentId, T scenario) {

        /** A repeated identity the operator refused: nothing was recorded, nothing resolved. */
        public boolean refused() {
            return scenario == null;
        }
    }

    private final TimelineEngine<?, T, ?> engine;
    private final ReferenceStore store;
    private final CallbackDispatcher<S> callbacks;
    private final PaymentIdentity identity;
    private final SubmissionCount count;

    Submissions(TimelineEngine<?, T, ?> engine, ReferenceStore store, CallbackDispatcher<S> callbacks,
                PaymentIdentity identity, SubmissionCount count) {
        this.engine = engine;
        this.store = store;
        this.callbacks = callbacks;
        this.identity = identity;
        this.count = count;
        boolean coherent = switch (identity.mintedBy()) {
            case CALLER -> identity.onRepeat() == PaymentIdentity.Repeat.REFUSED;
            case OPERATOR -> identity.onRepeat() == PaymentIdentity.Repeat.NOT_DEDUPLICATED;
        };
        if (!coherent) {
            throw new IllegalStateException("no face has declared an identity minted by " + identity.mintedBy()
                    + " together with repeats " + identity.onRepeat() + ", and the core does not guess what it"
                    + " would mean; see PaymentIdentity");
        }
    }

    /**
     * Submits one payment under {@code product}.
     *
     * @param reference   the reference the submission carried
     * @param callbackUrl the callback URL the submission named, or {@code null} to fall back
     *                    to the one declared in the control plane
     */
    public Submission<T> submit(Product product, String reference, String msisdn, String amount, String currency,
                                String callbackUrl) {
        return submit(product, reference, msisdn, amount, currency, callbackUrl, Map.of());
    }

    /**
     * As {@link #submit(Product, String, String, String, String, String)}, carrying
     * {@code callbackData} — what the face keeps from the submission to say its callbacks —
     * to every callback this submission schedules.
     */
    public Submission<T> submit(Product product, String reference, String msisdn, String amount, String currency,
                                String callbackUrl, Map<String, String> callbackData) {
        // Counted before anything can refuse it: a repeat the operator refuses was still a
        // call that reached it, which is exactly what a resend looks like (issue #175).
        count.increment();

        String paymentId = switch (identity.mintedBy()) {
            case CALLER -> identity.canonical(reference);
            case OPERATOR -> identity.mint(msisdn);
        };

        if (!store.record(product, paymentId)) {
            switch (identity.onRepeat()) {
                case REFUSED -> {
                    return new Submission<>(paymentId, null);
                }
                case NOT_DEDUPLICATED -> throw new IllegalStateException(
                        "the operator minted " + paymentId + ", an identity it had already recorded: a minted"
                                + " identity must be new, or two payments would answer to one");
            }
        }

        // Rules match the reference the caller chose: when the operator mints the identity,
        // nothing else about the payment existed for a rule to name.
        String matchedReference = reference == null ? null : identity.canonical(reference);
        T scenario = engine.resolveForSubmission(product, paymentId, matchedReference, msisdn, amount, currency);

        String url = (callbackUrl != null && !callbackUrl.isBlank()) ? callbackUrl : engine.callbackUrl();
        callbacks.schedule(paymentId, amount, currency, callbackData, scenario.callbacks(), url);

        return new Submission<>(paymentId, scenario);
    }
}
