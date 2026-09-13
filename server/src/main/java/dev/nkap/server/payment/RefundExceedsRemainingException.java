package dev.nkap.server.payment;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;

/**
 * Thrown by {@link Payment#reserveRefund} when a refund would push the total ever refunded
 * past what was ever collected. Mapped to a {@code 400} by {@code ApiExceptionHandler}, the
 * same way {@code NoAdapterConfiguredException} is — a domain rule signalled from
 * {@code server.payment}, translated to {@code problem+json} in {@code server.web} and
 * nowhere else.
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

    public ReferenceId original() {
        return original;
    }

    public Money remaining() {
        return remaining;
    }

    public Money requested() {
        return requested;
    }
}
