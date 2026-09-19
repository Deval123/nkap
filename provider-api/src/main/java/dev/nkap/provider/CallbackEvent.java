package dev.nkap.provider;

import dev.nkap.core.payment.ReferenceId;
import java.util.Objects;

/**
 * A callback an adapter has authenticated and understood.
 *
 * <p>Parsing a callback does not settle a payment. The gateway treats it as a hint and
 * confirms with {@code query()} before writing to the ledger, because a webhook can be
 * duplicated, delayed, replayed or forged, and the ledger cannot be un-written.
 *
 * <p><strong>{@code reference} may be {@code null}</strong> — issue #149, ADR 0011 §2. Some
 * operators' callbacks never carry any value the caller chose (M-Pesa's does not, observed
 * against a real sandbox: {@code docs/providers/m-pesa.md}), so an adapter for one cannot
 * produce Nkap's own {@link ReferenceId} the way MTN's does, by reading it back out of the
 * body. What such an adapter has instead is {@code providerReference} — the operator's own
 * identifier for the request, exactly as it named itself, the mirror image of
 * {@link QuerySubject#providerReference()}. The gateway resolves it against the
 * {@code reference ↔ provider_reference} association it has held on {@code payment} since
 * {@code V1__initial_schema.sql}, the same table {@code query} already reads in the other
 * direction (issue #96) — not the adapter, which 0008 already forbids from consulting gateway
 * state to do its job.
 *
 * <p>At least one of the two must be present; the compact constructor enforces it, the same
 * discipline {@link SubmitResult.Acknowledged} and {@link QuerySubject} already apply to their
 * own optional-but-not-both-absent fields.
 */
public record CallbackEvent(ReferenceId reference, String providerReference, ProviderStatus status) {

    public CallbackEvent {
        Objects.requireNonNull(status, "status");
        providerReference = providerReference == null ? "" : providerReference;
        if (reference == null && providerReference.isBlank()) {
            throw new IllegalArgumentException(
                    "a CallbackEvent must carry a reference, a provider reference, or both");
        }
    }

    /**
     * The shape every adapter written against {@code 1.0.0} already uses. Kept exactly as it
     * was — including rejecting a {@code null} reference, which this shape never allowed — so
     * that an existing adapter's construction of a {@link CallbackEvent} keeps compiling and
     * behaving identically, unchanged (ADR 0005's discipline, applied here; ADR 0011 §2, issue
     * #149).
     */
    public CallbackEvent(ReferenceId reference, ProviderStatus status) {
        this(Objects.requireNonNull(reference, "reference"), "", status);
    }

    /**
     * For an adapter that can only report the operator's own reference for a callback it
     * cannot itself resolve to a {@link ReferenceId} — issue #149. {@code providerReference}
     * must not be blank; there would then be nothing at all to attribute the callback with.
     */
    public static CallbackEvent unattributed(String providerReference, ProviderStatus status) {
        return new CallbackEvent(null, providerReference, status);
    }
}
