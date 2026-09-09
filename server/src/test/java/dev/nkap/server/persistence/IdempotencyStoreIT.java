package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.idempotency.IdempotencyKey;
import dev.nkap.core.idempotency.IdempotentOutcome;
import dev.nkap.core.idempotency.RequestFingerprint;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The idempotency store against PostgreSQL: the four outcomes of the {@code core}
 * reference, and — the point of doing it in SQL — {@code begin} staying atomic under real
 * concurrency, decided by one {@code INSERT … ON CONFLICT}, never a read then a write.
 */
@ExtendWith(DockerAvailable.class)
class IdempotencyStoreIT {

    private static PostgresIdempotencyStore store;

    @BeforeAll
    static void connect() {
        store = new PostgresIdempotencyStore(PostgresDatabase.shared().jdbcTemplate());
    }

    private static IdempotencyKey freshKey() {
        return new IdempotencyKey("merchant-1", UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("the four outcomes: Proceed, then InProgress, then Conflict on a different body, then Replay once complete")
    void the_four_outcomes() {
        IdempotencyKey key = freshKey();
        RequestFingerprint body = RequestFingerprint.of("body-A");

        assertThat(store.begin(key, body)).isInstanceOf(IdempotentOutcome.Proceed.class);
        assertThat(store.begin(key, body)).isInstanceOf(IdempotentOutcome.InProgress.class);
        assertThat(store.begin(key, RequestFingerprint.of("body-B"))).isInstanceOf(IdempotentOutcome.Conflict.class);

        store.complete(key, "the-stored-response");

        IdempotentOutcome replayed = store.begin(key, body);
        assertThat(replayed).isInstanceOfSatisfying(IdempotentOutcome.Replay.class,
                r -> assertThat(r.storedResponse()).isEqualTo("the-stored-response"));
    }

    @Test
    @DisplayName("abandon frees an unanswered claim, and does nothing to a completed one")
    void abandon_frees_only_an_unanswered_claim() {
        IdempotencyKey unanswered = freshKey();
        store.begin(unanswered, RequestFingerprint.of("x"));
        store.abandon(unanswered);
        assertThat(store.begin(unanswered, RequestFingerprint.of("x"))).isInstanceOf(IdempotentOutcome.Proceed.class);

        IdempotencyKey completed = freshKey();
        store.begin(completed, RequestFingerprint.of("y"));
        store.complete(completed, "done");
        store.abandon(completed);
        assertThat(store.begin(completed, RequestFingerprint.of("y"))).isInstanceOf(IdempotentOutcome.Replay.class);
    }

    @Test
    @DisplayName("two concurrent begin calls with the same key: exactly one Proceed")
    void concurrent_begins_yield_exactly_one_proceed() throws Exception {
        IdempotencyKey key = freshKey();
        RequestFingerprint body = RequestFingerprint.of("concurrent");
        int threads = 16;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IdempotentOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return store.begin(key, body);
            }));
        }
        start.countDown();

        long proceeds = 0;
        for (Future<IdempotentOutcome> future : futures) {
            if (future.get() instanceof IdempotentOutcome.Proceed) {
                proceeds++;
            }
        }
        pool.shutdown();

        assertThat(proceeds).isEqualTo(1);
    }
}
