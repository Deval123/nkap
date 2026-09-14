package dev.nkap.server.payment;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;

/**
 * Thrown when a refund would push the total ever refunded past what was ever collected —
 * either by {@link Payment#reserveRefund}'s own courtesy check against an unlocked snapshot,
 * or by {@code PaymentRepository.reserveRefund} when the database's {@code CHECK} (V8)
 * refuses the atomic {@code UPDATE} that is the actual guarantee. The two are not the same
 * claim: the first is a fast, precise rejection of the common case; the second is what a
 * concurrent refund that raced past the first one hits instead. Mapped to a {@code 400} by
 * {@code ApiExceptionHandler}, the same way {@code NoAdapterConfiguredException} is — a
 * domain rule signalled from {@code server.payment}, translated to {@code problem+json} in
 * {@code server.web} and nowhere else.
 */
public final class RefundExceedsRemainingException extends RuntimeException {

    private final ReferenceId original;
    private final Money remaining;
    private final Money requested;

    public RefundExceedsRemainingException(ReferenceId original, Money remaining, Money requested) {
        super(original + " has " + remaining + " left to refund; this request was for " + requested);
        this.original = original;
        this.remaining = remaining;
        this.requested = requested;
    }

    /**
     * For the database path: by the time the {@code CHECK} has refused the {@code UPDATE},
     * the transaction is already aborted, and re-reading the exact remaining balance would
     * need a second one — not worth it for what is, by definition, the rare, racing case.
     */
    public RefundExceedsRemainingException(ReferenceId original, Money requested) {
        super("refunding " + original + " by " + requested + " would exceed what remains of it");
        this.original = original;
        this.remaining = null;
        this.requested = requested;
    }

    public ReferenceId original() {
        return original;
    }

    /** {@code null} when this came from the database path rather than {@link Payment#reserveRefund}'s own check. */
    public Money remaining() {
        return remaining;
    }

    public Money requested() {
        return requested;
    }
}
