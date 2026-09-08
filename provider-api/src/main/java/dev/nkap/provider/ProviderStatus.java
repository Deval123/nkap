package dev.nkap.provider;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;

import java.util.Objects;
import java.util.Optional;

/**
 * The provider's answer about one payment.
 *
 * <p>Anything an adapter cannot map with confidence becomes {@link PaymentState#UNKNOWN}.
 * Mapping an unrecognised status to {@code FAILED} is the single most expensive bug this
 * project exists to prevent.
 */
public record ProviderStatus(
        PaymentState state,
        String providerStatusCode,
        String providerTransactionId,
        Money providerFee,
        String failureReason,
        String rawResponse) {

    public ProviderStatus {
        Objects.requireNonNull(state, "state");
        providerStatusCode = providerStatusCode == null ? "" : providerStatusCode;
        providerTransactionId = providerTransactionId == null ? "" : providerTransactionId;
        failureReason = failureReason == null ? "" : failureReason;
        rawResponse = rawResponse == null ? "" : rawResponse;
    }

    public Optional<Money> fee() {
        return Optional.ofNullable(providerFee);
    }

    /**
     * The provider's own id for the settled movement — MTN's {@code financialTransactionId}.
     * The reconciler matches it against an operator statement. Absent while the payment is
     * still pending, so an {@link Optional}.
     */
    public Optional<String> transactionId() {
        return providerTransactionId.isBlank() ? Optional.empty() : Optional.of(providerTransactionId);
    }

    public static ProviderStatus unknown(String providerStatusCode, String rawResponse) {
        return new ProviderStatus(PaymentState.UNKNOWN, providerStatusCode, "", null, "", rawResponse);
    }
}
