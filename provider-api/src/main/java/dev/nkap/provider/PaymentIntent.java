package dev.nkap.provider;

import dev.nkap.core.money.Money;

import java.util.Map;
import java.util.Objects;

/**
 * What the caller asked for, in provider-neutral terms.
 *
 * <p>An intent carries no state and no identifier: the {@code ReferenceId} is passed
 * separately to {@code submit}, because it is Nkap's, not the caller's.
 */
public record PaymentIntent(
        Capability operation,
        Money amount,
        String counterpartyMsisdn,
        String payerMessage,
        String payeeNote,
        Map<String, String> providerOptions) {

    public PaymentIntent {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(counterpartyMsisdn, "counterpartyMsisdn");
        payerMessage = payerMessage == null ? "" : payerMessage;
        payeeNote = payeeNote == null ? "" : payeeNote;
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(providerOptions);

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A payment intent must be for a positive amount, was " + amount);
        }
        if (operation != Capability.COLLECT && operation != Capability.DISBURSE) {
            throw new IllegalArgumentException(operation + " is not a payment operation");
        }
    }
}
