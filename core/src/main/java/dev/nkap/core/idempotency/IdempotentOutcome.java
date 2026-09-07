package dev.nkap.core.idempotency;

/**
 * What the store says about a request carrying an idempotency key.
 *
 * <p>Four cases, and the caller must handle all four — which is why this is a sealed
 * hierarchy rather than a nullable return.
 */
public sealed interface IdempotentOutcome {

    /** First time this key is seen. Proceed, then record the response. */
    record Proceed(IdempotencyKey key) implements IdempotentOutcome {}

    /** Same key, same body, already answered. Replay the stored response verbatim. */
    record Replay(IdempotencyKey key, String storedResponse) implements IdempotentOutcome {}

    /** Same key, different body. The client has a bug; tell it so with a 409. */
    record Conflict(IdempotencyKey key) implements IdempotentOutcome {}

    /**
     * Same key, still being processed. Also a 409: do not wait, do not run it twice.
     * The client should retry after the first attempt has settled.
     */
    record InProgress(IdempotencyKey key) implements IdempotentOutcome {}
}
