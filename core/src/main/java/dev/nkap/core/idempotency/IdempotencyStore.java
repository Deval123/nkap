package dev.nkap.core.idempotency;

/**
 * Remembers which requests have been seen, and what was answered.
 *
 * <p>This is the upstream half of idempotency — between the caller and Nkap. The
 * downstream half, between Nkap and the provider, is handled by reusing the same
 * {@link dev.nkap.core.payment.ReferenceId} on every retry.
 */
public interface IdempotencyStore {

    /**
     * Claims the key for this request, or reports what should happen instead.
     * Must be atomic: two concurrent calls with the same key cannot both get
     * {@link IdempotentOutcome.Proceed}.
     */
    IdempotentOutcome begin(IdempotencyKey key, RequestFingerprint fingerprint);

    /** Records the response produced for a key claimed by {@link #begin}. */
    void complete(IdempotencyKey key, String response);

    /**
     * Releases a claim whose request could not be answered, so the caller may retry.
     * Never called after {@link #complete}.
     */
    void abandon(IdempotencyKey key);
}
