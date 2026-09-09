package dev.nkap.server.reconcile;

import dev.nkap.server.payment.ConfirmationOutcome;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.reconcile.ReconciliationStore.Claim;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Nothing stays {@code UNKNOWN} forever.
 *
 * <p>On each pass the reconciler claims a bounded batch of due {@code UNKNOWN} payments,
 * and for each one asks the operator again through {@link SettlementService#confirm} — the
 * same write path a callback uses, so a payment the reconciler resolves settles exactly
 * once and its transition is attributed to {@code RECONCILER}. A payment the operator
 * confirms leaves {@code UNKNOWN} and drops out of the queue on its own; the claim already
 * recorded this attempt and scheduled the next, further out.
 *
 * <p>When a payment's retry window is spent — {@link ReconciliationPolicy#windowExhausted}
 * — the reconciler <strong>escalates</strong> it: {@code escalated_at} is stamped, a human
 * is paged through one WARN line, and the automatic retries stop. It does <strong>not</strong>
 * move to {@code FAILED}. Giving up waiting is not the operator saying the payment failed,
 * and this system never makes that inference. An escalated payment is still {@code UNKNOWN}
 * and a later callback or a later manual query can still resolve it.
 *
 * <p>The operator call sits outside the claim transaction, for the reason written twice
 * elsewhere in this codebase: an operator that does not answer must not hold a database
 * transaction open for its whole timeout. The claim takes the row, advances its schedule,
 * commits; the call happens with no lock held; {@code confirm} then re-takes the row to
 * write any result. Because the claim pushes the next-due time forward before it commits,
 * a second reconciler instance neither races on the row nor re-claims it once released.
 *
 * <p>Built and scheduled by {@link ReconcilerConfiguration}, which is switched off by
 * {@code nkap.reconciler.enabled=false} — the integration tests that are not about the
 * reconciler set that and drive {@link #runOnce()} by hand instead of waiting for a tick.
 */
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final ReconciliationStore store;
    private final SettlementService settlement;
    private final ReconciliationPolicy policy;
    private final ReconcilerProperties properties;
    private final Clock clock;

    public Reconciler(ReconciliationStore store, SettlementService settlement, ReconciliationPolicy policy,
                      ReconcilerProperties properties, Clock clock) {
        this.store = store;
        this.settlement = settlement;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
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
        Instant now = clock.instant();
        List<Claim> claims = store.claimDue(properties.batchSize(), now);
        for (Claim claim : claims) {
            ConfirmationOutcome outcome =
                    settlement.confirm(claim.provider(), claim.reference(), PaymentTransition.Cause.RECONCILER);
            if (outcome.resolved()) {
                continue;
            }
            if (policy.windowExhausted(claim.unknownSince(), now) && store.markEscalated(claim.reference(), now)) {
                log.warn("payment {} escalated to a human after {} reconciler attempt(s); operator's last answer: {}",
                        claim.reference(), claim.attempts(), outcome.lastOperatorAnswer());
            }
        }
        return claims.size();
    }
}
