package dev.nkap.server.adjustment;

/**
 * The two settings the feature has. Off unless {@code nkap.adjustments.enabled} is
 * {@code true}; and while on, no proposal moves more than {@code maxAmountMinor} minor units
 * (the sum of its debits). The ceiling is one number across currencies, so in a currency with
 * two minor-unit decimals it buys a hundredth of what it does in XAF.
 */
public record AdjustmentPolicy(boolean enabled, long maxAmountMinor) {

    public AdjustmentPolicy {
        if (maxAmountMinor <= 0) {
            throw new IllegalArgumentException(
                    "nkap.adjustments.max-amount-minor must be a positive number of minor units, was " + maxAmountMinor);
        }
    }
}
