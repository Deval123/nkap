package dev.nkap.provider;

import dev.nkap.core.money.Money;

import java.util.Map;
import java.util.Objects;

/**
 * What the caller asked for, in provider-neutral terms.
 *
 * <p>An intent carries no state and no identifier: the {@code ReferenceId} is passed
 * separately to {@code submit}, because it is Nkap's, not the caller's.
 *
 * <p>{@code operation} is a {@link Capability.Operation}, not a {@link Capability}. An intent
 * for {@code BALANCE} or {@code STATEMENT} used to be rejected at construction, by a runtime
 * check that repeated what the type now says on its own: those are {@link Capability.Feature},
 * not something a caller submits, so there is no constructor here that accepts one.
 */
public record PaymentIntent(
        Capability.Operation operation,
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
    }
}
