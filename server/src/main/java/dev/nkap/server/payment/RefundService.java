package dev.nkap.server.payment;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.server.provider.AdapterRegistry;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a validated refund request into a new {@code DISBURSE} payment, reserved against
 * the collection it refunds before the operator is ever asked (issue #84, ADR 0010).
 *
 * <p>{@code RefundController} has already checked the rules that do not depend on
 * concurrency — the original is a {@code SUCCEEDED} collection belonging to this merchant,
 * the amount is positive and does not exceed what the controller's own (unlocked) read of
 * the remaining balance allows. What is left here is the one check that <strong>does</strong>
 * depend on concurrency: two refunds for the same collection, issued at once, must not both
 * pass. That is {@link Payment#reserveRefund}, called under the original's row lock in the
 * same transaction that persists the new refund row — so a crash between the two would
 * never leave a reservation with no payment behind it, or a refund payment with no
 * reservation counted against it.
 *
 * <p>The adapter is resolved <strong>before</strong> that transaction opens, for the same
 * reason {@link PaymentService#createAndSubmit} resolves it before persisting anything: the
 * contract every caller of a {@code *Service} in this package relies on is that a thrown
 * exception means nothing durable happened yet, so {@code RefundController} can release the
 * idempotency claim. It never should throw here — a refund's provider is always the
 * original's, already known to be configured — but "should never happen" is exactly the
 * case this ordering is cheap insurance against.
 *
 * <p>Submitting to the operator and applying whatever it answers is not duplicated here: it
 * is {@link PaymentService#submit}, the same method {@code createAndSubmit} uses, called for
 * a payment this class persisted itself rather than one {@code PaymentService} created. The
 * state machine, the settlement path, the reconciler, escalation, the outbox, the webhooks
 * and the conformance kit needed no change at all — a refund is a payment.
 */
@Service
public class RefundService {

    private final PaymentRepository payments;
    private final AdapterRegistry adapters;
    private final PaymentService paymentService;
    private final TransactionTemplate tx;

    public RefundService(PaymentRepository payments, AdapterRegistry adapters, PaymentService paymentService,
                         PlatformTransactionManager txManager) {
        this.payments = payments;
        this.adapters = adapters;
        this.paymentService = paymentService;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Reserves {@code amount} against {@code original} and submits the refund. Returns the
     * refund in whichever state the submission left it — {@code SUBMITTED} / {@code PENDING}
     * on acknowledgement, {@code FAILED} on an outright refusal, {@code UNKNOWN} on no answer
     * — exactly the same possibilities {@code createAndSubmit} documents, because this is
     * that same call.
     *
     * <p>Throws {@link RefundExceedsRemainingException} if a concurrent refund already spent
     * the amount {@code original} had left, re-checked under lock — the controller's own
     * check of the same rule is necessarily stale by the time this runs. Never persists
     * anything before that check passes.
     */
    public Payment createAndSubmit(Payment original, Money amount) {
        ProviderAdapter adapter = adapters.require(original.provider());
        ReferenceId originalReference = original.reference();
        ReferenceId refundReference = ReferenceId.newReference();

        tx.executeWithoutResult(status -> {
            Payment locked = payments.findByReferenceForUpdate(originalReference).orElseThrow(() ->
                    new IllegalStateException("refund of " + originalReference + " but the original vanished"));
            locked.reserveRefund(amount);
            payments.save(locked);

            PaymentIntent refundIntent = new PaymentIntent(Capability.Operation.DISBURSE, amount,
                    locked.intent().counterpartyMsisdn(), "refund", "refund of " + originalReference, Map.of());
            Payment refund = Payment.createRefund(refundReference, locked.provider(), original.merchantId(),
                    refundIntent, originalReference);
            payments.save(refund);
        });

        Payment refund = payments.findByReference(refundReference).orElseThrow();
        // The reference as a structured field, matching PaymentService.createAndSubmit and
        // SettlementService — one refund's story, filterable across all three (issue #75).
        try (var ignored = MDC.putCloseable("reference", refundReference.toString())) {
            return paymentService.submit(adapter, refund.intent(), refundReference);
        }
    }
}
