package dev.nkap.server.payment;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import dev.nkap.server.outbox.OutboxNotifier;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.provider.PublicBaseUrl;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a payment, hands it to the operator, and records honestly what came back.
 *
 * <p>The order is the point: the payment is persisted in {@link PaymentState#CREATED}
 * <strong>before</strong> the adapter is called. A crash between the two leaves a payment
 * the reconciler can find; without that write, a request that reached the operator but not
 * the response is money lost to everyone.
 *
 * <p>Once the payment is persisted, nothing escapes: the operator may already hold the
 * request, so an unexpected failure past that line is not-knowing, not failure — recorded
 * {@link PaymentState#UNKNOWN} and logged loudly, never {@code FAILED}. Only
 * {@code adapters.require} and {@code Payment.create}, both before the first save, may
 * throw, which is what lets {@code PaymentController} release the idempotency claim.
 *
 * <p>The submit response is applied inside one transaction that takes the payment's row
 * with {@code SELECT … FOR UPDATE}. A callback for this reference can arrive and be
 * confirmed while {@code adapter.submit} is still in flight; if it has advanced the payment
 * out of {@code CREATED}, this response is stale and the callback already recorded the
 * truth. The submit <strong>call</strong> stays outside the transaction — an operator that
 * does not answer would otherwise hold a database transaction open for the whole timeout.
 * The transaction opens when the answer is in hand.
 *
 * <p>Every log line this method or the ones it calls emit carries the reference as a
 * structured field ({@code MDC}), not only inside the sentence — the same field
 * {@code SettlementService} and the reconciler attach, so one payment's story across all
 * three can be filtered on it rather than grepped for (issue #75).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final AdapterRegistry adapters;
    private final OutboxNotifier notifier;
    private final PublicBaseUrl publicBaseUrl;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, AdapterRegistry adapters, OutboxNotifier notifier,
                          PublicBaseUrl publicBaseUrl, PlatformTransactionManager txManager) {
        this.payments = payments;
        this.adapters = adapters;
        this.notifier = notifier;
        this.publicBaseUrl = publicBaseUrl;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * The {@code Proceed} path of {@code POST /payments}. Returns the payment in whichever
     * state the submission left it: {@code SUBMITTED} / {@code PENDING} on acknowledgement,
     * {@code FAILED} on an outright refusal, {@code UNKNOWN} on no answer or an unexpected
     * error on our side — or a state a callback reached first, if one confirmed the payment
     * before this response arrived. Once the payment is persisted it never throws.
     */
    public Payment createAndSubmit(ProviderId providerId, String merchantId, PaymentIntent intent) {
        ProviderAdapter adapter = adapters.require(providerId);

        ReferenceId reference = ReferenceId.newReference();
        // The reference as a structured field, not just interpolated into each sentence
        // below: everything this call logs, and everything SettlementService or the
        // reconciler log later about the same payment, can be filtered on it (issue #75).
        try (var ignored = MDC.putCloseable("reference", reference.toString())) {
            // The callback URL is composed here, not by PaymentController, because it
            // carries this payment's own reference (issue #185) -- which does not exist
            // until the line above. PaymentIntent.providerOptions() as it reaches this
            // point carries nothing PaymentController put there.
            PaymentIntent withCallback = withCallbackUrl(intent, providerId, reference);
            Payment created = Payment.create(reference, providerId, merchantId, withCallback);
            // Which endpoint this payment is actually being submitted to, from
            // configuration, before the operator is ever called (issue #122).
            adapters.settlementEndpoint(providerId).ifPresent(created::recordProviderBaseUrl);
            payments.save(created);
            return submit(adapter, withCallback, reference);
        }
    }

    /** {@code intent}, with {@code providerOptions} replaced by this submission's own callback URL (issue #185). */
    private PaymentIntent withCallbackUrl(PaymentIntent intent, ProviderId providerId, ReferenceId reference) {
        return new PaymentIntent(intent.operation(), intent.amount(), intent.counterpartyMsisdn(),
                intent.payerMessage(), intent.payeeNote(), publicBaseUrl.providerOptionsFor(providerId, reference));
    }

    /**
     * The submit-and-apply half of {@link #createAndSubmit}, for a payment already persisted
     * in {@link PaymentState#CREATED} under {@code reference} — split out so
     * {@code RefundService} can reuse the exact same call-the-operator-and-apply-whatever-it-
     * says logic for a refund it persisted itself, under its own reservation on the
     * collection being refunded, without this class needing to know refunds exist. Every
     * guarantee {@code createAndSubmit}'s javadoc makes about what escapes and what does not
     * applies here unchanged; the split moved no behaviour.
     */
    Payment submit(ProviderAdapter adapter, PaymentIntent intent, ReferenceId reference) {
        if (!adapter.operations().contains(intent.operation())) {
            // The gateway's own routing fact, known before any call is made: the adapter is
            // never asked (ADR 0013, change 1). After this check exists, reaching it at all
            // means some caller of PaymentService ignored ProviderAdapter.capabilities() —
            // a programming error, not a payment outcome, which is why it is still worth
            // recording plainly rather than treating as impossible.
            String note = "payment asks for " + intent.operation() + " but the adapter declares "
                    + adapter.operations();
            log.info("submit for {} refused without calling the adapter: {}", reference, note);
            return applyAndSave(reference, Optional.empty(), current -> {
                current.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.GATEWAY, "", note, "");
                RefundReservations.release(payments, current);
            });
        }

        SubmitOutcome outcome = callOperator(adapter, intent, reference);
        return applyAndSave(reference, outcome.providerReference(), current -> applyOutcome(outcome, current, reference));
    }

    /**
     * The transactional envelope every submit response shares, whatever produced it: lock the
     * payment row, bail out if a callback already advanced it past {@link PaymentState#CREATED}
     * while the caller was deciding what to record — recording {@code providerReference} first
     * if this response still has one to offer — otherwise let {@code apply} record the
     * transition, then notify if it left the payment terminal. {@code providerReference} is
     * empty for anything that was never a {@link SubmitResult.Acknowledged}, including the
     * gateway's own pre-call refusal above: there is nothing to keep in the stale case either
     * way.
     */
    private Payment applyAndSave(ReferenceId reference, Optional<String> providerReference, Consumer<Payment> apply) {
        tx.executeWithoutResult(status -> {
            Payment current = payments.findByReferenceForUpdate(reference).orElseThrow();
            if (current.state() != PaymentState.CREATED) {
                // A callback confirmed this payment while the submit call was in flight.
                // The response is stale — the callback path already recorded the
                // transitions, and forcing CREATED -> SUBMITTED now would be illegal.
                providerReference.ifPresent(current::recordProviderReference);
                log.info("submit response for {} is stale: a callback already advanced it to {}",
                        reference, current.state());
                payments.save(current);
                return;
            }
            apply.accept(current);
            // Same transaction as the save() below: an outcome that moves the payment
            // straight to FAILED is a terminal verdict a merchant is waiting for too, not
            // only the ones SettlementService reaches later (issue #77).
            notifier.notifyIfTerminal(current);
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
            // A defect on our side. Most of what still reaches this catch does so after a
            // request may already be out — a bug in how a response was parsed, say — so
            // UNKNOWN, not FAILED, stays the only honest record: this catch cannot tell
            // "after" from "before" apart, and recording FAILED here would be the one
            // conclusion this project forbids. That was also true, until ADR 0013, for both
            // MTN adapters' guards throwing IllegalArgumentException before any call was
            // ever made — a true-looking sentence beside code that made it false. The
            // currency guard no longer throws at all (SubmitResult.NotAttempted, handled
            // below); the operation guard still does, but submit()'s capability check above
            // means reaching it here is now only a caller ignoring
            // ProviderAdapter.capabilities(), which this catch cannot distinguish from a
            // genuine in-flight defect either — UNKNOWN is still the honest answer for both.
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
            case SubmitResult.Rejected rejected -> {
                payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.SUBMIT_RESPONSE,
                        rejected.providerCode(), rejected.reason(), rejected.rawResponse());
                // A no-op unless this payment is a refund (issue #84): an outright rejection
                // of the transfer releases the amount it had reserved on the collection it
                // refunds, since it now never will move that money.
                RefundReservations.release(payments, payment);
            }
            case SubmitResult.NotAttempted notAttempted -> {
                // The adapter refused before calling the operator (ADR 0013, change 2) —
                // GATEWAY, the same cause submit()'s own pre-call refusal above uses:
                // whichever of the two decided, no operator spoke.
                payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.GATEWAY,
                        "", notAttempted.reason(), "");
                RefundReservations.release(payments, payment);
            }
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
