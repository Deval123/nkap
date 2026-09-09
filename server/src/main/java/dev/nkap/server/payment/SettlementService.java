package dev.nkap.server.payment;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.ledger.LedgerInvariantViolation;
import dev.nkap.core.ledger.Posting;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.server.provider.AdapterRegistry;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a callback into, at most, a confirmed transition and one ledger entry.
 *
 * <p>A callback is a hint, never evidence: it can be duplicated, delayed, replayed or
 * forged, and the endpoint is unauthenticated. So nothing here trusts the callback's
 * contents. It triggers a call to {@code adapter.query}, and <strong>the operator's answer
 * to that is the only authority</strong>. The worst a forged callback achieves is making
 * the gateway ask a question it was entitled to ask.
 *
 * <p>Everything is arranged so a wrong belief costs a query, never an entry:
 *
 * <ul>
 *   <li>an already-terminal payment is left alone — a late callback cannot reopen it;</li>
 *   <li>if the confirming query does not answer, or answers {@code UNKNOWN}, nothing
 *       changes — moving a payment to {@code UNKNOWN} because <em>our</em> second opinion
 *       was inconclusive would destroy what we did know;</li>
 *   <li>the ledger entry id is derived from the reference, so a second settlement is
 *       refused by {@link Ledger#append} rather than written twice.</li>
 * </ul>
 *
 * <p>The whole read-decide-write runs under {@link ReferenceLocks}: two callbacks for one
 * reference, or a callback racing the submit that created the payment, serialise here.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final PaymentRepository payments;
    private final AdapterRegistry adapters;
    private final Ledger ledger;
    private final ReferenceLocks locks;

    public SettlementService(PaymentRepository payments, AdapterRegistry adapters, Ledger ledger, ReferenceLocks locks) {
        this.payments = payments;
        this.adapters = adapters;
        this.ledger = ledger;
        this.locks = locks;
    }

    /**
     * Confirms a callback for a payment this gateway holds, and applies whatever the
     * operator's {@code query} then reports. Does nothing, silently, when there is nothing
     * safe to do. Never throws for a provider problem.
     */
    public void confirm(ProviderId providerId, ReferenceId reference) {
        locks.run(reference, () -> {
            Payment payment = payments.findByReference(reference).orElse(null);
            if (payment == null) {
                return;
            }
            if (payment.state().isTerminal()) {
                log.debug("callback for {} ignored: payment is already {}", reference, payment.state());
                return;
            }

            ProviderStatus status;
            try {
                status = adapters.require(providerId).query(reference);
            } catch (ProviderUnavailableException noAnswer) {
                log.info("callback for {}: the confirming query did not answer, changing nothing: {}",
                        reference, noAnswer.getMessage());
                return;
            }

            PaymentState confirmed = status.state();
            if (confirmed == PaymentState.UNKNOWN) {
                log.info("callback for {}: the operator's answer is not conclusive ({}), changing nothing",
                        reference, blankToDash(status.providerStatusCode()));
                return;
            }
            if (confirmed == payment.state()) {
                return;
            }

            // A callback for a still-CREATED payment is itself proof the operator received
            // the request — that is what SUBMITTED means. Record that leg, then the one the
            // query reports. The state machine is not widened; both transitions happened.
            if (payment.state() == PaymentState.CREATED && !payment.state().canTransitionTo(confirmed)) {
                payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.CALLBACK,
                        "", "callback received before the submit response", status.rawResponse());
            }

            if (!payment.state().canTransitionTo(confirmed)) {
                log.warn("callback for {}: operator reports {} but payment is {}; changing nothing",
                        reference, confirmed, payment.state());
                return;
            }

            payment.applyTransition(confirmed, PaymentTransition.Cause.CALLBACK,
                    status.providerStatusCode(), status.failureReason(), status.rawResponse());
            status.transactionId().ifPresent(payment::recordProviderTransactionId);

            if (payment.state() == PaymentState.SUCCEEDED) {
                settle(payment);
            }
            payments.save(payment);
        });
    }

    /**
     * Posts the gross amount, two postings, per ADR 0006. No fee posting — fees arrive
     * later through statement reconciliation. Exactly-once is the ledger's own rule: the
     * entry id is derived from the reference, so a repeat is refused, not overwritten.
     */
    private void settle(Payment payment) {
        Money gross = payment.intent().amount();
        Currency currency = gross.currency();
        AccountId providerFloat = AccountId.providerFloat(payment.provider().toString(), currency);
        AccountId merchantPayable = AccountId.merchantPayable(payment.merchantId(), currency);

        LedgerEntry entry = new LedgerEntry(
                entryId(payment.reference()),
                Instant.now(),
                payment.reference().toString(),
                "Collection settled: " + gross + " for " + payment.merchantId(),
                List.of(Posting.debit(providerFloat, gross), Posting.credit(merchantPayable, gross)));
        try {
            ledger.append(entry);
            log.info("settled {}: DR {} / CR {} {}", payment.reference(), providerFloat, merchantPayable, gross);
        } catch (LedgerInvariantViolation alreadyRecorded) {
            // The per-reference lock already prevents a second attempt here; this is the
            // structural backstop for the PostgreSQL era, where two nodes race on the row.
            log.info("settlement entry for {} was already recorded; not writing it again", payment.reference());
        }
    }

    private static String entryId(ReferenceId reference) {
        return "collection:" + reference;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
