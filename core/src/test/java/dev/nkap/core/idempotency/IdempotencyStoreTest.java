package dev.nkap.core.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyStoreTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("acme", "order-1");
    private static final RequestFingerprint BODY = RequestFingerprint.of("{\"amount\":5000}");
    private static final RequestFingerprint OTHER_BODY = RequestFingerprint.of("{\"amount\":9000}");

    private final IdempotencyStore store = new InMemoryIdempotencyStore();

    @Test
    @DisplayName("a key seen for the first time proceeds")
    void firstCallProceeds() {
        assertInstanceOf(IdempotentOutcome.Proceed.class, store.begin(KEY, BODY));
    }

    @Test
    @DisplayName("the same key with the same body replays the stored response")
    void sameBodyReplays() {
        store.begin(KEY, BODY);
        store.complete(KEY, "{\"reference\":\"abc\"}");

        IdempotentOutcome outcome = store.begin(KEY, BODY);
        IdempotentOutcome.Replay replay = assertInstanceOf(IdempotentOutcome.Replay.class, outcome);
        assertEquals("{\"reference\":\"abc\"}", replay.storedResponse());
    }

    @Test
    @DisplayName("the same key with a different body is a conflict, never a silent second payment")
    void differentBodyConflicts() {
        store.begin(KEY, BODY);
        store.complete(KEY, "{\"reference\":\"abc\"}");

        assertInstanceOf(IdempotentOutcome.Conflict.class, store.begin(KEY, OTHER_BODY));
    }

    @Test
    @DisplayName("a key still being processed is rejected rather than run twice")
    void concurrentCallIsRejected() {
        store.begin(KEY, BODY);
        assertInstanceOf(IdempotentOutcome.InProgress.class, store.begin(KEY, BODY));
    }

    @Test
    @DisplayName("an abandoned attempt frees the key for a retry")
    void abandonedKeyCanBeRetried() {
        store.begin(KEY, BODY);
        store.abandon(KEY);

        assertInstanceOf(IdempotentOutcome.Proceed.class, store.begin(KEY, BODY));
    }

    @Test
    @DisplayName("an answered key is not released by a late abandon")
    void abandonDoesNotUndoACompletedCall() {
        store.begin(KEY, BODY);
        store.complete(KEY, "{\"reference\":\"abc\"}");
        store.abandon(KEY);

        assertInstanceOf(IdempotentOutcome.Replay.class, store.begin(KEY, BODY));
    }

    @Test
    @DisplayName("keys are scoped per merchant")
    void keysAreScopedPerMerchant() {
        store.begin(KEY, BODY);
        store.complete(KEY, "{\"reference\":\"abc\"}");

        assertInstanceOf(IdempotentOutcome.Proceed.class,
                store.begin(new IdempotencyKey("globex", "order-1"), BODY));
    }

    @Test
    @DisplayName("a blank key is refused at the door")
    void blankKeyRefused() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey("acme", "  "));
    }
}
