package dev.nkap.simulator;

import dev.nkap.simulator.scenario.CallbackStep;
import dev.nkap.simulator.scenario.Timeline;
import dev.nkap.simulator.scenario.TimelineEngine;
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

    Submissions(TimelineEngine<?, T, ?> engine, ReferenceStore store, CallbackDispatcher<S> callbacks,
                PaymentIdentity identity) {
        this.engine = engine;
        this.store = store;
        this.callbacks = callbacks;
        this.identity = identity;
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
        String paymentId = switch (identity.mintedBy()) {
            case CALLER -> identity.canonical(reference);
        };

        if (!store.record(product, paymentId)) {
            boolean refused = switch (identity.onRepeat()) {
                case REFUSED -> true;
            };
            if (refused) {
                return new Submission<>(paymentId, null);
            }
        }

        T scenario = engine.resolveForSubmission(product, paymentId, msisdn, amount, currency);

        String url = (callbackUrl != null && !callbackUrl.isBlank()) ? callbackUrl : engine.callbackUrl();
        callbacks.schedule(paymentId, amount, currency, scenario.callbacks(), url);

        return new Submission<>(paymentId, scenario);
    }
}
