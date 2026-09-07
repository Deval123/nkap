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
        Money providerFee,
        String failureReason,
        String rawResponse) {

    public ProviderStatus {
        Objects.requireNonNull(state, "state");
        providerStatusCode = providerStatusCode == null ? "" : providerStatusCode;
        failureReason = failureReason == null ? "" : failureReason;
        rawResponse = rawResponse == null ? "" : rawResponse;
    }

    public Optional<Money> fee() {
        return Optional.ofNullable(providerFee);
    }

    public static ProviderStatus unknown(String providerStatusCode, String rawResponse) {
        return new ProviderStatus(PaymentState.UNKNOWN, providerStatusCode, null, "", rawResponse);
    }
}
