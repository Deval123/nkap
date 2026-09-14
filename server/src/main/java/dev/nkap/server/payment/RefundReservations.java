package dev.nkap.server.payment;

/**
 * The one place that releases a refund's reservation on its original collection — called
 * from both {@link PaymentService} (a refund rejected outright at submit) and
 * {@link SettlementService} (a refund the operator later confirms {@code FAILED} or
 * {@code EXPIRED}), so the rule lives once rather than being repeated at each call site.
 *
 * <p>A no-op for every payment that is not a refund ({@link Payment#refundOf()} empty), so
 * both callers can invoke this unconditionally on any payment reaching a terminal state,
 * refund or not.
 */
final class RefundReservations {

    private RefundReservations() {
    }

    /**
     * Releases {@code refund}'s reserved amount on the collection it refunds — a single
     * atomic {@code UPDATE} ({@link PaymentRepository#releaseRefundReservation}), not a
     * load-mutate-save of a locked copy: the same reasoning as
     * {@link PaymentRepository#reserveRefund}, so this call cannot itself become the
     * lost-update bug the reservation side was fixed for (issue #84).
     */
    static void release(PaymentRepository payments, Payment refund) {
        refund.refundOf().ifPresent(originalReference ->
                payments.releaseRefundReservation(originalReference, refund.intent().amount()));
    }
}
