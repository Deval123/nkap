package dev.nkap.server.reconcile;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import java.time.Instant;
import java.util.List;

/**
 * The reconciler's two writes against the payment table: claim a batch of due work, and
 * flag a payment as escalated.
 *
 * <p>Claiming is the part that has to be right under more than one instance. It runs in
 * one transaction that takes each due row with {@code SELECT … FOR UPDATE SKIP LOCKED} and,
 * before it commits, pushes that row's next attempt into the future. A second reconciler
 * running at the same moment skips the rows this one holds; once this transaction commits,
 * the rows it claimed are no longer due, so the second reconciler does not pick them up on
 * its next pass either. The operator call happens afterwards, outside any transaction.
 */
public interface ReconciliationStore {

    /**
     * Claims up to {@code batch} payments that are unresolved ({@code SUBMITTED},
     * {@code PENDING} or {@code UNKNOWN}), not escalated, and due at or before {@code now};
     * advances each claimed payment's attempt count and next-due time by the policy; and
     * returns what was claimed. One transaction, {@code FOR UPDATE SKIP LOCKED}.
     */
    List<Claim> claimDue(int batch, Instant now);

    /**
     * Stamps {@code escalated_at} on a payment, but only if it is still unresolved and not
     * already escalated — so a payment resolved between the claim and now is left alone.
     * Returns whether it actually escalated.
     */
    boolean markEscalated(ReferenceId reference, Instant at);

    /**
     * One claimed payment: which provider to ask, how many attempts it has now had, and
     * when it became unresolved — the instant the escalation window is measured from.
     * {@code unresolvedSince} is {@code null} only for a payment that predates the column
     * and slipped past its backfill.
     */
    record Claim(ProviderId provider, ReferenceId reference, int attempts, Instant unresolvedSince) {
    }
}
