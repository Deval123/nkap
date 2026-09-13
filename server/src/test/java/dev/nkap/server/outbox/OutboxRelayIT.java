package dev.nkap.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.LogCapture;
import dev.nkap.server.support.PostgresDatabase;
import dev.nkap.server.support.StubReceiver;
import dev.nkap.server.webhook.InMemoryWebhookEndpointStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link OutboxRelay}, against a real PostgreSQL and a real (if throwaway) HTTP receiver.
 * Tests 2, 4, 5 and 6 of issue #77's plan; test 1 (the transactional coupling) is
 * {@code OutboxTransactionIT} — deliberately a different file, since that one is about a
 * write commit, not delivery.
 */
@ExtendWith(DockerAvailable.class)
class OutboxRelayIT {

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void connect() {
        PostgresDatabase db = PostgresDatabase.shared();
        jdbc = db.jdbcTemplate();
        txManager = db.transactionManager();
    }

    private static UUID insertEvent(String merchantId, String eventType, String payload) {
        UUID id = UUID.randomUUID();
        new PostgresOutbox(jdbc, txManager).append(new OutboxEvent(id, merchantId, eventType, payload));
        return id;
    }

    private static InMemoryWebhookEndpointStore endpointFor(String merchantId, String url, String secret) {
        InMemoryWebhookEndpointStore endpoints = new InMemoryWebhookEndpointStore();
        endpoints.provisionWithSecret(secret, merchantId, url);
        return endpoints;
    }

    // --- test 2: growing interval, dead-lettered after the configured attempts, findable ---

    @Test
    @DisplayName("a receiver that always fails is retried on a growing interval and dead-lettered after the configured attempts")
    void a_failing_receiver_is_retried_then_dead_lettered() throws Exception {
        try (StubReceiver receiver = new StubReceiver()) {
            receiver.alwaysRespond(500, "{}");
            UUID id = insertEvent("merchant-1", "payment.succeeded", "{\"id\":\"" + UUID.randomUUID() + "\"}");
            InMemoryWebhookEndpointStore endpoints = endpointFor("merchant-1", receiver.baseUrl().toString(), "whsec_test");

            AdjustableClock clock = new AdjustableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
            OutboxRelayPolicy policy = new OutboxRelayPolicy(Duration.ofMinutes(1), Duration.ofMinutes(30), 3);
            OutboxRelay relay = relayWith(endpoints, policy, clock, Duration.ofSeconds(2));

            relay.runOnce();
            assertThat(attempts(id)).isEqualTo(1);
            assertThat(deadLetteredAt(id)).isNull();
            Instant dueAfterFirst = nextAttemptAt(id);
            assertThat(dueAfterFirst).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));

            clock.set(dueAfterFirst);
            relay.runOnce();
            assertThat(attempts(id)).isEqualTo(2);
            assertThat(deadLetteredAt(id)).isNull();
            Instant dueAfterSecond = nextAttemptAt(id);
            assertThat(dueAfterSecond)
                    .as("the interval grows: attempt 2's delay (2m) is longer than attempt 1's (1m)")
                    .isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));

            clock.set(dueAfterSecond);
            relay.runOnce();
            assertThat(attempts(id)).isEqualTo(3);
            assertThat(deadLetteredAt(id))
                    .as("the third attempt exhausts maxAttempts=3")
                    .isNotNull();
            assertThat(lastError(id)).isNotBlank();

            // Dead-lettered, never dropped: the row and its content are still there, the
            // same way OutboxRelayStore.find (WebhookReplayController's read) reports it.
            OutboxRelayStore store = new PostgresOutboxRelayStore(jdbc, txManager, policy);
            assertThat(store.find(id)).isPresent();
            assertThat(store.find(id).orElseThrow().merchantId()).isEqualTo("merchant-1");

            // A dead-lettered event is not claimed again by a later pass.
            clock.set(clock.instant().plus(Duration.ofDays(1)));
            int claimedAgain = relay.runOnce();
            assertThat(claimedAgain).isZero();
            assertThat(receiver.requests).hasSize(3);
        }
    }

    // --- test 4: the same event delivered twice carries the same event id ---

    @Test
    @DisplayName("the same event delivered twice — a failed attempt, then a successful retry — carries the same event id both times")
    void the_same_event_delivered_twice_carries_the_same_id() throws Exception {
        try (StubReceiver receiver = new StubReceiver()) {
            AtomicInteger calls = new AtomicInteger();
            receiver.respondWith(request -> calls.incrementAndGet() == 1
                    ? new StubReceiver.StubResponse(500, "{}")
                    : new StubReceiver.StubResponse(200, "{}"));

            UUID id = insertEvent("merchant-2", "payment.failed", "{\"id\":\"evt\"}");
            InMemoryWebhookEndpointStore endpoints = endpointFor("merchant-2", receiver.baseUrl().toString(), "whsec_test");

            AdjustableClock clock = new AdjustableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
            OutboxRelayPolicy policy = new OutboxRelayPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 5);
            OutboxRelay relay = relayWith(endpoints, policy, clock, Duration.ofSeconds(2));

            relay.runOnce();
            clock.set(nextAttemptAt(id));
            relay.runOnce();

            assertThat(receiver.requests).hasSize(2);
            assertThat(receiver.requests).extracting(r -> r.header("Nkap-Event-Id"))
                    .as("at-least-once delivery: retried, but every attempt names the same event id — the field a merchant deduplicates on")
                    .containsExactly(id.toString(), id.toString());
            assertThat(deliveredAt(id)).isNotNull();
        }
    }

    // --- test 5: a slow receiver does not hold a database transaction ---

    @Test
    @DisplayName("a slow receiver does not hold a database transaction — the claim already committed before the call")
    void a_slow_receiver_does_not_hold_a_transaction() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        try (StubReceiver receiver = new StubReceiver()) {
            receiver.respondWith(request -> {
                requestReceived.countDown();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StubReceiver.StubResponse(200, "{}");
            });

            UUID id = insertEvent("merchant-3", "payment.succeeded", "{\"id\":\"evt\"}");
            InMemoryWebhookEndpointStore endpoints = endpointFor("merchant-3", receiver.baseUrl().toString(), "whsec_test");
            OutboxRelayPolicy policy = new OutboxRelayPolicy(Duration.ofMinutes(1), Duration.ofMinutes(30), 5);
            OutboxRelay relay = relayWith(endpoints, policy, Clock.fixed(Instant.now(), ZoneOffset.UTC), Duration.ofSeconds(5));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> pass = executor.submit(relay::runOnce);

                assertThat(requestReceived.await(2, TimeUnit.SECONDS))
                        .as("the receiver was actually called").isTrue();

                // The receiver is now sleeping mid-request. If the claim transaction were
                // still open, this row would be locked and a NOWAIT attempt would fail
                // immediately with a lock-not-available error. It succeeds, so the claim
                // (and its FOR UPDATE) already committed before the HTTP call started.
                try (Connection probe = PostgresDatabase.shared().dataSource().getConnection()) {
                    probe.setAutoCommit(false);
                    try (var statement = probe.prepareStatement(
                            "SELECT 1 FROM outbox_event WHERE id = ? FOR UPDATE NOWAIT")) {
                        statement.setObject(1, id);
                        assertThatDoesNotLock(statement);
                    } finally {
                        probe.rollback();
                    }
                }

                assertThat(pass.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void assertThatDoesNotLock(PreparedStatement statement) throws SQLException {
        try {
            statement.executeQuery();
        } catch (SQLException lockNotAvailable) {
            throw new AssertionError(
                    "the outbox row was still locked while the receiver was mid-request — "
                            + "the claim transaction is being held open across the HTTP call", lockNotAvailable);
        }
    }

    // --- test 6: no secret appears in a log line ---

    @Test
    @DisplayName("no secret appears in a log line, on success or on failure")
    void no_secret_appears_in_a_log_line() throws Exception {
        String secret = "whsec_must-never-be-logged";
        try (StubReceiver receiver = new StubReceiver();
             LogCapture logs = new LogCapture(OutboxRelay.class)) {
            receiver.respondWith(request -> new StubReceiver.StubResponse(500, "{}"));

            UUID id = insertEvent("merchant-4", "payment.expired", "{\"id\":\"evt\"}");
            InMemoryWebhookEndpointStore endpoints = endpointFor("merchant-4", receiver.baseUrl().toString(), secret);
            OutboxRelayPolicy policy = new OutboxRelayPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
            OutboxRelay relay = relayWith(endpoints, policy, Clock.systemUTC(), Duration.ofSeconds(2));

            relay.runOnce();

            for (ILoggingEvent event : logs.events()) {
                assertThat(event.getFormattedMessage()).doesNotContain(secret);
            }
        }
    }

    private static OutboxRelay relayWith(InMemoryWebhookEndpointStore endpoints, OutboxRelayPolicy policy,
                                         Clock clock, Duration requestTimeout) {
        OutboxRelayStore store = new PostgresOutboxRelayStore(jdbc, txManager, policy);
        WebhookSender sender = new WebhookSender(requestTimeout, clock);
        OutboxRelayProperties properties = new OutboxRelayProperties(
                Duration.ofSeconds(10), 10, Duration.ofMinutes(1), Duration.ofMinutes(30), 5,
                requestTimeout, Duration.ofMinutes(5));
        return new OutboxRelay(store, endpoints, sender, policy, properties, clock);
    }

    private static Integer attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, id);
    }

    private static Instant nextAttemptAt(UUID id) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM outbox_event WHERE id = ?", OffsetDateTime.class, id)
                .toInstant();
    }

    private static Instant deadLetteredAt(UUID id) {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT dead_lettered_at FROM outbox_event WHERE id = ?", OffsetDateTime.class, id);
        return value == null ? null : value.toInstant();
    }

    private static Instant deliveredAt(UUID id) {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT delivered_at FROM outbox_event WHERE id = ?", OffsetDateTime.class, id);
        return value == null ? null : value.toInstant();
    }

    private static String lastError(UUID id) {
        return jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE id = ?", String.class, id);
    }

    /** A clock the test moves by hand, so backoff maths is checked without waiting for real time. */
    private static final class AdjustableClock extends Clock {

        private volatile Instant now;

        AdjustableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
