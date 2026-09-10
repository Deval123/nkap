package dev.nkap.server.payment;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.DuplicateLedgerEntryException;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.ledger.Posting;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.server.provider.AdapterRegistry;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a prompt to look — a callback, or the reconciler's turn — into, at most, a
 * confirmed transition and one ledger entry.
 *
 * <p>There is <strong>one</strong> write path here on purpose. A callback and a reconciler
 * pass want exactly the same thing: take the payment under lock, ask the operator, apply
 * the answer only if it is conclusive, settle once on {@code SUCCEEDED}, change nothing
 * when the query does not answer. The only difference is the {@link PaymentTransition.Cause}
 * recorded on the transition — {@code CALLBACK} or {@code RECONCILER} — so that is a
 * parameter and nothing else is duplicated. A second settlement path is how two behaviours
 * drift apart, and this one writes to the ledger.
 *
 * <p>A callback is a hint, never evidence: it can be duplicated, delayed, replayed or
 * forged, and the endpoint is unauthenticated. So nothing here trusts the callback's
 * contents. It triggers {@code adapter.query}, and <strong>the operator's answer to that is
 * the only authority</strong>. The worst a forged callback achieves is making the gateway
 * ask a question it was entitled to ask.
 *
 * <p>Everything is arranged so a wrong belief costs a query, never an entry:
 *
 * <ul>
 *   <li>an already-terminal payment is left alone — a late callback cannot reopen it;</li>
 *   <li>if the confirming query does not answer, or answers {@code UNKNOWN}, nothing
 *       changes — moving a payment to {@code UNKNOWN} because <em>our</em> second opinion
 *       was inconclusive would destroy what we did know;</li>
 *   <li>the entry and the payment's new state are written in <strong>one transaction</strong>:
 *       either both land or neither does. A settled payment with no entry, or an entry with
 *       no settled payment, is the worst state this system can be in.</li>
 * </ul>
 *
 * <p>{@code query} runs <strong>outside</strong> the transaction, for the reason the submit
 * call does: an operator that does not answer must not hold a database transaction open for
 * the whole timeout. The transaction opens once the answer is in hand, takes the payment's
 * row with {@code SELECT … FOR UPDATE}, and closes when the writes are done — which is also
 * what serialises two callbacks for one reference.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final PaymentRepository payments;
    private final AdapterRegistry adapters;
    private final Ledger ledger;
    private final TransactionTemplate tx;

    public SettlementService(PaymentRepository payments, AdapterRegistry adapters, Ledger ledger,
                             PlatformTransactionManager txManager) {
        this.payments = payments;
        this.adapters = adapters;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * The one write path for "ask the operator, then apply whatever it conclusively says",
     * shared by the callback endpoint ({@code cause = CALLBACK}) and the reconciler
     * ({@code cause = RECONCILER}). Does nothing, silently, when there is nothing safe to
     * do. Never throws for a provider problem. Returns what happened, for a caller — the
     * reconciler — that has to decide what to do next.
     */
    public ConfirmationOutcome confirm(ProviderId providerId, ReferenceId reference, PaymentTransition.Cause cause) {
        Payment peek = payments.findByReference(reference).orElse(null);
        if (peek == null) {
            return ConfirmationOutcome.notHeld();
        }
        if (peek.state().isTerminal()) {
            log.debug("{} for {} ignored: payment is already {}", cause, reference, peek.state());
            return ConfirmationOutcome.alreadyResolved(peek.state());
        }

        ProviderStatus status;
        try {
            status = adapters.require(providerId).query(reference);
        } catch (ProviderUnavailableException noAnswer) {
            log.info("{} for {}: the confirming query did not answer, changing nothing: {}",
                    cause, reference, noAnswer.getMessage());
            return ConfirmationOutcome.noAnswer();
        }

        return tx.execute(txStatus -> applyConfirmed(reference, status, cause));
    }

    /** The read-decide-write, in one transaction, on the row locked with {@code FOR UPDATE}. */
    private ConfirmationOutcome applyConfirmed(ReferenceId reference, ProviderStatus status, PaymentTransition.Cause cause) {
        Payment payment = payments.findByReferenceForUpdate(reference).orElseThrow();
        if (payment.state().isTerminal()) {
            return ConfirmationOutcome.alreadyResolved(payment.state());
        }

        PaymentState confirmed = status.state();
        if (confirmed == PaymentState.UNKNOWN) {
            log.info("{} for {}: the operator's answer is not conclusive ({}), changing nothing",
                    cause, reference, blankToDash(status.providerStatusCode()));
            return ConfirmationOutcome.inconclusive(payment.state(), status.providerStatusCode());
        }
        if (confirmed == payment.state()) {
            return ConfirmationOutcome.inconclusive(payment.state(), status.providerStatusCode());
        }

        // A callback for a still-CREATED payment is itself proof the operator received the
        // request — that is what SUBMITTED means. Record that leg, then the one the query
        // reports. The state machine is not widened; both transitions happened.
        if (payment.state() == PaymentState.CREATED && !payment.state().canTransitionTo(confirmed)) {
            payment.applyTransition(PaymentState.SUBMITTED, cause,
                    "", "callback received before the submit response", status.rawResponse());
        }

        if (!payment.state().canTransitionTo(confirmed)) {
            log.warn("{} for {}: operator reports {} but payment is {}; changing nothing",
                    cause, reference, confirmed, payment.state());
            return ConfirmationOutcome.inconclusive(payment.state(), status.providerStatusCode());
        }

        payment.applyTransition(confirmed, cause,
                status.providerStatusCode(), status.failureReason(), status.rawResponse());
        status.transactionId().ifPresent(payment::recordProviderTransactionId);

        if (payment.state() == PaymentState.SUCCEEDED) {
            settle(payment);
        }
        payments.save(payment);
        // A terminal state is a verdict and the payment leaves the queue. A move to another
        // non-terminal state — UNKNOWN -> PENDING — is progress, not a resolution: the
        // reconciler must keep chasing it and must still escalate it if its window is spent.
        return payment.state().isTerminal()
                ? ConfirmationOutcome.resolved(payment.state(), status.providerStatusCode())
                : ConfirmationOutcome.advanced(payment.state(), status.providerStatusCode());
    }

    /**
     * Posts the gross amount, two postings, per ADR 0006. No fee posting — fees arrive
     * later through statement reconciliation.
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
        } catch (DuplicateLedgerEntryException alreadyRecorded) {
            // Only this exact type is swallowed. The row lock on the payment already makes a
            // second settlement impossible within one node; across nodes the id derived
            // from the reference plus this catch is the guard. Any other integrity error —
            // a zero-sum failure raised at commit, a foreign key — must abort the
            // transaction, taking the payment's state change down with it.
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
