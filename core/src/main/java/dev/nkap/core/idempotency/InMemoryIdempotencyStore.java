package dev.nkap.core.idempotency;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Reference implementation of {@link IdempotencyStore}, for tests and for reading. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Record(RequestFingerprint fingerprint, String response) {

        boolean isComplete() {
            return response != null;
        }
    }

    private final Map<IdempotencyKey, Record> records = new ConcurrentHashMap<>();

    @Override
    public IdempotentOutcome begin(IdempotencyKey key, RequestFingerprint fingerprint) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");

        Record existing = records.putIfAbsent(key, new Record(fingerprint, null));
        if (existing == null) {
            return new IdempotentOutcome.Proceed(key);
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return new IdempotentOutcome.Conflict(key);
        }
        if (!existing.isComplete()) {
            return new IdempotentOutcome.InProgress(key);
        }
        return new IdempotentOutcome.Replay(key, existing.response());
    }

    @Override
    public void complete(IdempotencyKey key, String response) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(response, "response");
        records.computeIfPresent(key, (k, existing) -> new Record(existing.fingerprint(), response));
    }

    @Override
    public void abandon(IdempotencyKey key) {
        Objects.requireNonNull(key, "key");
        records.computeIfPresent(key, (k, existing) -> existing.isComplete() ? existing : null);
    }
}
