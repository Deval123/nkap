package dev.nkap.provider;

import dev.nkap.core.payment.ReferenceId;

import java.util.Objects;

/**
 * A callback an adapter has authenticated and understood.
 *
 * <p>Parsing a callback does not settle a payment. The gateway treats it as a hint and
 * confirms with {@code query()} before writing to the ledger, because a webhook can be
 * duplicated, delayed, replayed or forged, and the ledger cannot be un-written.
 */
public record CallbackEvent(ReferenceId reference, ProviderStatus status) {

    public CallbackEvent {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(status, "status");
    }
}
