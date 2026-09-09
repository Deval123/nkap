package dev.nkap.server.payment;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import dev.nkap.server.provider.AdapterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Creates a payment, hands it to the operator, and records honestly what came back.
 *
 * <p>The order is the point: the payment is persisted in {@link PaymentState#CREATED}
 * <strong>before</strong> the adapter is called. A crash between the two leaves a payment
 * the reconciler can find; without that write, a request that reached the operator but not
 * the response is money lost to everyone.
 *
 * <p>The other half of that guarantee is structural: once {@code payments.save} has run,
 * <strong>nothing escapes this method</strong>. The operator may already hold the request,
 * so an unexpected failure past that line is not-knowing, not failure — it is recorded
 * {@link PaymentState#UNKNOWN} and logged loudly. Only {@code adapters.require} and
 * {@code Payment.create}, both before the save, may throw, which is what lets
 * {@code PaymentController} release the idempotency claim safely when they do.
 *
 * <p>Applying the submit response is serialised with the callback path through
 * {@link ReferenceLocks}. A callback for this reference can arrive and be confirmed while
 * {@code adapter.submit} is still in flight; if it advances the payment out of
 * {@code CREATED}, this response is stale and the callback already recorded the truth.
 * The submit <em>call</em> is made outside the lock — it can block for the whole request
 * timeout, and a callback must not wait that long.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final AdapterRegistry adapters;
    private final ReferenceLocks locks;

    public PaymentService(PaymentRepository payments, AdapterRegistry adapters, ReferenceLocks locks) {
        this.payments = payments;
        this.adapters = adapters;
        this.locks = locks;
    }

    /**
     * The {@code Proceed} path of {@code POST /payments}. Returns the payment in whichever
     * state the submission left it: {@code SUBMITTED} / {@code PENDING} on acknowledgement,
     * {@code FAILED} on an outright refusal by the operator, {@code UNKNOWN} when the
     * operator did not answer or when submitting failed unexpectedly on our side —
     * or {@code SUCCEEDED} / {@code FAILED} already, when a callback confirmed the payment
     * before this response arrived. Once the payment is persisted it never throws.
     */
    public Payment createAndSubmit(ProviderId providerId, String merchantId, PaymentIntent intent) {
        ProviderAdapter adapter = adapters.require(providerId);

        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, providerId, merchantId, intent);
        payments.save(payment);

        SubmitOutcome outcome = callOperator(adapter, intent, reference);

        locks.run(reference, () -> {
            Payment current = payments.findByReference(reference).orElseThrow();
            if (current.state() != PaymentState.CREATED) {
                // A callback confirmed this payment while the submit call was in flight.
                // The response is stale — the callback path already recorded the
                // transitions, and forcing CREATED -> SUBMITTED now would be illegal.
                outcome.providerReference().ifPresent(current::recordProviderReference);
                log.info("submit response for {} is stale: a callback already advanced it to {}",
                        reference, current.state());
                payments.save(current);
                return;
            }
            applyOutcome(outcome, current, reference);
            payments.save(current);
        });

        return payments.findByReference(reference).orElseThrow();
    }

    private SubmitOutcome callOperator(ProviderAdapter adapter, PaymentIntent intent, ReferenceId reference) {
        try {
            return new SubmitOutcome(adapter.submit(intent, reference), null, null);
        } catch (ProviderUnavailableException noAnswer) {
            return new SubmitOutcome(null, noAnswer, null);
        } catch (RuntimeException unexpected) {
            return new SubmitOutcome(null, null, unexpected);
        }
    }

    private void applyOutcome(SubmitOutcome outcome, Payment payment, ReferenceId reference) {
        if (outcome.noAnswer() != null) {
            log.info("submit for {} did not answer; recording UNKNOWN: {}", reference, outcome.noAnswer().getMessage());
            payment.applyTransition(payment.state().onProviderTimeout(), PaymentTransition.Cause.SUBMIT_RESPONSE,
                    "", outcome.noAnswer().getMessage(), "");
            return;
        }
        if (outcome.unexpected() != null) {
            // A defect on our side, after the point where the operator may already have the
            // request. In the data this is indistinguishable from an operator timeout, so
            // only this log tells them apart: it must be loud, with the stack trace.
            // Recording FAILED here would be the one conclusion this project forbids.
            log.error("submit for {} failed unexpectedly; recording UNKNOWN, not FAILED", reference,
                    outcome.unexpected());
            payment.applyTransition(payment.state().onProviderTimeout(), PaymentTransition.Cause.SUBMIT_RESPONSE,
                    "", "unexpected error while submitting: " + outcome.unexpected(), "");
            return;
        }
        switch (outcome.result()) {
            case SubmitResult.Acknowledged acknowledged -> {
                payment.recordProviderReference(acknowledged.providerReference());
                payment.applyTransition(acknowledged.state(), PaymentTransition.Cause.SUBMIT_RESPONSE,
                        "", "", acknowledged.rawResponse());
            }
            case SubmitResult.Rejected rejected -> payment.applyTransition(PaymentState.FAILED,
                    PaymentTransition.Cause.SUBMIT_RESPONSE,
                    rejected.providerCode(), rejected.reason(), rejected.rawResponse());
        }
    }

    /** What {@code adapter.submit} produced: an acknowledgement or rejection, a "no answer", or a bug. */
    private record SubmitOutcome(SubmitResult result, ProviderUnavailableException noAnswer, RuntimeException unexpected) {

        Optional<String> providerReference() {
            return result instanceof SubmitResult.Acknowledged acknowledged && !acknowledged.providerReference().isBlank()
                    ? Optional.of(acknowledged.providerReference())
                    : Optional.empty();
        }
    }
}
