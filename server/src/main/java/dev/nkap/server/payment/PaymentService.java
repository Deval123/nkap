package dev.nkap.server.payment;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import dev.nkap.server.provider.AdapterRegistry;
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
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final AdapterRegistry adapters;

    public PaymentService(PaymentRepository payments, AdapterRegistry adapters) {
        this.payments = payments;
        this.adapters = adapters;
    }

    /**
     * The {@code Proceed} path of {@code POST /payments}. Returns the payment in whichever
     * state the submission left it: {@code SUBMITTED} / {@code PENDING} on acknowledgement,
     * {@code FAILED} on an outright refusal, {@code UNKNOWN} when the operator did not
     * answer. It never throws for a provider problem — silence is {@code UNKNOWN}, not an
     * error.
     */
    public Payment createAndSubmit(ProviderId providerId, String merchantId, PaymentIntent intent) {
        ProviderAdapter adapter = adapters.require(providerId);

        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, providerId, merchantId, intent);
        payments.save(payment);

        try {
            SubmitResult result = adapter.submit(intent, reference);
            switch (result) {
                case SubmitResult.Acknowledged acknowledged -> {
                    payment.recordProviderReference(acknowledged.providerReference());
                    payment.applyTransition(acknowledged.state(), PaymentTransition.Cause.SUBMIT_RESPONSE,
                            "", "", acknowledged.rawResponse());
                }
                case SubmitResult.Rejected rejected -> payment.applyTransition(PaymentState.FAILED,
                        PaymentTransition.Cause.SUBMIT_RESPONSE,
                        rejected.providerCode(), rejected.reason(), rejected.rawResponse());
            }
        } catch (ProviderUnavailableException noAnswer) {
            log.info("submit for {} did not answer; recording UNKNOWN: {}", reference, noAnswer.getMessage());
            payment.applyTransition(payment.state().onProviderTimeout(), PaymentTransition.Cause.SUBMIT_RESPONSE,
                    "", noAnswer.getMessage(), "");
        }

        payments.save(payment);
        return payment;
    }
}
