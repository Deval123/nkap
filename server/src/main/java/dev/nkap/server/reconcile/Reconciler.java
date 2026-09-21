package dev.nkap.server.reconcile;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.provider.Resolution;
import dev.nkap.server.payment.ConfirmationOutcome;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.reconcile.ReconciliationStore.Claim;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nothing stays unresolved forever.
 *
 * <p>On each pass the reconciler claims a bounded batch of due <strong>unresolved</strong>
 * payments — {@code SUBMITTED}, {@code PENDING} or {@code UNKNOWN}, every state that has
 * left {@code CREATED} and not reached a verdict — and asks the operator again through
 * {@link SettlementService#confirm}, the same write path a callback uses, so a payment the
 * reconciler resolves settles exactly once and its transition is attributed to
 * {@code RECONCILER}. A payment the operator drives to a terminal state drops out of the
 * queue on its own; one it merely moves between non-terminal states stays in it, still
 * chased. The claim has already recorded this attempt and scheduled the next, further out.
 *
 * <p>When a payment's retry window is spent — {@link ReconciliationPolicy#windowExhausted},
 * wall-clock time since it became unresolved — the reconciler <strong>escalates</strong>
 * it: {@code escalated_at} is stamped, a human is paged through one WARN line, and the
 * automatic retries stop. It does <strong>not</strong> move to {@code FAILED}. Giving up
 * waiting is not the operator saying the payment failed, and this system never makes that
 * inference. An escalated payment is still non-terminal and a later callback or a later
 * manual query can still resolve it. Every escalation also increments
 * {@code nkap.payment.escalated}, a counter tagged {@code provider} and {@code reason} — the
 * second of the two alerting rules the plan asked to ship (issue #75; see
 * {@code docs/prometheus-alerts.yml}).
 *
 * <p><strong>One claim never reaches the operator at all (ADR 0014 decision 3, issue
 * #188).</strong> Before {@code confirm} is called, a claim whose adapter does not declare
 * {@link dev.nkap.provider.Resolution#QUERY} <em>and</em> whose payment holds no provider
 * reference — {@link Payment#cannotBeQueriedBy} — is escalated immediately, with zero
 * attempts and no operator call. Backing off and re-asking on a schedule assumes the next
 * ask might answer; here it provably cannot, because there is nothing to ask with and no way
 * to be told the answer for that request specifically. Escalating instead of polling for the
 * whole window is the only runtime behaviour change that ADR makes, and it is a separate
 * reason to escalate, not a shorter {@link ReconciliationPolicy#windowExhausted} window: every
 * other unresolved payment is still chased exactly as before. No adapter declares only
 * {@code CALLBACK} today, so this path is dormant until one does.
 *
 * <p>The operator call sits outside the claim transaction, for the reason written twice
 * elsewhere in this codebase: an operator that does not answer must not hold a database
 * transaction open for its whole timeout. The claim takes the row, advances its schedule,
 * commits; the call happens with no lock held; {@code confirm} then re-takes the row to
 * write any result. Because the claim pushes the next-due time forward before it commits,
 * a second reconciler instance neither races on the row nor re-claims it once released.
 *
 * <p>Each pass generates its own id and carries it as a structured log field
 * ({@code MDC}) for the pass's whole duration — including, since {@code confirm} runs on
 * this same thread, every log line {@link SettlementService} emits while resolving one of
 * this pass's claims. "Which pass wrote this" is the question asked right after "what
 * happened to this payment" (issue #75).
 *
 * <p>Built and scheduled by {@link ReconcilerConfiguration}, which is switched off by
 * {@code nkap.reconciler.enabled=false} — the integration tests that are not about the
 * reconciler set that and drive {@link #runOnce()} by hand instead of waiting for a tick.
 *
 * <p><strong>A second, narrower job: a refund stranded in {@code CREATED} (issue #84's
 * second correction).</strong> {@code RefundService} commits a refund's reservation and its
 * {@code CREATED} row in one transaction, then calls the operator in a second step; if the
 * process is killed in between, or that second step fails after the first already
 * committed, the row is left in {@code CREATED} holding a reservation nothing will ever
 * release, because {@code PaymentState.isUnresolved()} deliberately excludes {@code CREATED}
 * and this reconciler would otherwise never notice it. Every pass first sweeps refund
 * payments ({@code refund_of IS NOT NULL}) still {@code CREATED} past
 * {@code nkap.reconciler.stranded-refund-grace} and moves each to {@code UNKNOWN} — a legal
 * transition {@code PaymentState} already allows — from where the ordinary claim loop below
 * chases it exactly like any other unresolved payment. An ordinary {@code CREATED} collection
 * or disbursement is left alone: it was never inert in the way a stranded refund now is, and
 * widening what {@code isUnresolved()} covers is a larger, separate behaviour change this fix
 * does not make. See {@code docs/providers/mtn.md} for what a query on a reference the
 * operator never saw actually returns, and why that means a genuinely stranded refund
 * escalates rather than resolving itself.
 */
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final PaymentRepository payments;
    private final ReconciliationStore store;
    private final SettlementService settlement;
    private final AdapterRegistry adapters;
    private final ReconciliationPolicy policy;
    private final ReconcilerProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate tx;

    public Reconciler(PaymentRepository payments, ReconciliationStore store, SettlementService settlement,
                      AdapterRegistry adapters, ReconciliationPolicy policy, ReconcilerProperties properties,
                      Clock clock, MeterRegistry meterRegistry, PlatformTransactionManager txManager) {
        this.payments = payments;
        this.store = store;
        this.settlement = settlement;
        this.adapters = adapters;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${nkap.reconciler.interval}")
    void scheduledPass() {
        try {
            runOnce();
        } catch (RuntimeException failed) {
            // A pass that blew up must not kill the scheduler thread: the next pass will
            // re-claim whatever this one left, its schedule already advanced.
            log.error("reconciler pass failed; the next pass will retry the same payments", failed);
        }
    }

    /**
     * One pass: claim the due batch, confirm each through the shared path, escalate the
     * ones whose window is spent. Returns how many payments were claimed — for the
     * scheduled caller's logs and for the tests.
     */
    public int runOnce() {
        String passId = UUID.randomUUID().toString();
        try (var ignored = MDC.putCloseable("reconcilerPass", passId)) {
            Instant now = clock.instant();
            sweepStrandedRefunds(now);
            List<Claim> claims = store.claimDue(properties.batchSize(), now);
            log.debug("reconciler pass {} claimed {} payment(s)", passId, claims.size());
            for (Claim claim : claims) {
                if (cannotBeQueried(claim)) {
                    if (store.markEscalated(claim.reference(), now)) {
                        log.warn("payment {} escalated to a human immediately: provider {} does not declare "
                                        + "Resolution.QUERY and this payment holds no provider reference, so no "
                                        + "reconciler attempt could ever resolve it -- no attempt was made",
                                claim.reference(), claim.provider());
                        escalated(claim.provider().toString(), "cannot_query");
                    }
                    continue;
                }
                ConfirmationOutcome outcome =
                        settlement.confirm(claim.provider(), claim.reference(), PaymentTransition.Cause.RECONCILER);
                if (outcome.resolved()) {
                    continue;
                }
                if (policy.windowExhausted(claim.unresolvedSince(), now) && store.markEscalated(claim.reference(), now)) {
                    log.warn("payment {} escalated to a human after {} reconciler attempt(s); operator's last answer: {}",
                            claim.reference(), claim.attempts(), outcome.lastOperatorAnswer());
                    escalated(claim.provider().toString(), "window_exhausted");
                }
            }
            return claims.size();
        }
    }

    /**
     * Moves every refund still {@code CREATED} past the grace period to {@code UNKNOWN},
     * one row lock at a time — see the class javadoc for why this exists at all. Each
     * transition is a fresh {@code findByReferenceForUpdate} and a re-check of the state,
     * not the snapshot {@code findStrandedRefunds} returned: a refund that resolved in the
     * moment between that read and this write is left alone rather than forced backwards.
     */
    private void sweepStrandedRefunds(Instant now) {
        Instant olderThan = now.minus(properties.strandedRefundGrace());
        for (Payment stale : payments.findStrandedRefunds(olderThan)) {
            tx.executeWithoutResult(status -> {
                Payment locked = payments.findByReferenceForUpdate(stale.reference()).orElse(null);
                if (locked == null || locked.state() != PaymentState.CREATED) {
                    return;
                }
                locked.applyTransition(PaymentState.UNKNOWN, PaymentTransition.Cause.RECONCILER, "",
                        "stranded in CREATED past " + properties.strandedRefundGrace()
                                + " -- the operator may or may not have received it", "");
                payments.save(locked);
                log.warn("refund {} was stranded in CREATED and moved to UNKNOWN for this reconciler to chase",
                        stale.reference());
            });
        }
    }

    /**
     * ADR 0014 decision 3's trigger, checked cheaply: the adapter lookup is in memory, so a
     * claim whose adapter declares {@code QUERY} — every adapter today — never pays for the
     * payment row read {@link Payment#cannotBeQueriedBy} also needs. Only once an adapter
     * lacks {@code QUERY} does this go back to the database to check the one thing that can
     * still save it: a provider reference from a submission that did get answered.
     */
    private boolean cannotBeQueried(Claim claim) {
        return adapters.find(claim.provider())
                .filter(adapter -> !adapter.resolves().contains(Resolution.QUERY))
                .flatMap(adapter -> payments.findByReference(claim.reference()).map(payment -> payment.cannotBeQueriedBy(adapter)))
                .orElse(false);
    }

    private void escalated(String provider, String reason) {
        Counter.builder("nkap.payment.escalated")
                .description("Payments the reconciler gave up retrying automatically. Still open, not FAILED "
                        + "-- needs a human. reason=window_exhausted is the ordinary case; "
                        + "reason=cannot_query (ADR 0014 decision 3) is a payment escalated with zero reconciler "
                        + "attempts because its adapter cannot resolve a lost submission by polling and it has no "
                        + "provider reference to query with.")
                .tag("provider", provider)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
