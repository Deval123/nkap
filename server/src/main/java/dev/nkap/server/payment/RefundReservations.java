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
     * Releases {@code refund}'s reserved amount on the collection it refunds, under that
     * collection's own row lock — the caller already holds an open transaction, the same one
     * {@code refund}'s own terminal transition is being saved in.
     */
    static void release(PaymentRepository payments, Payment refund) {
        refund.refundOf().ifPresent(originalReference -> {
            Payment original = payments.findByReferenceForUpdate(originalReference).orElseThrow(() ->
                    new IllegalStateException(refund.reference() + " is a refund of " + originalReference
                            + ", but that payment no longer exists"));
            original.releaseRefundReservation(refund.intent().amount());
            payments.save(original);
        });
    }
}
