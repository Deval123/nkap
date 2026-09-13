package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.outbox.Outbox;
import dev.nkap.server.outbox.OutboxEvent;
import dev.nkap.server.outbox.OutboxRelayStore;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code GET /webhooks/events/dead-lettered} — the read half of dead-lettering that never
 * shipped. {@code dead_lettered_at} was written and nothing ever read it back; the only route
 * in was a replay that required already knowing the event id. This is the same rule
 * {@code PaymentRepository.findEscalated()} follows for a payment the reconciler gave up on:
 * stop trying, make it findable, never decide it did not matter. Admin-gated, the same
 * credential class as {@code GET /statements/imports/{id}}, because this is operator data
 * across merchants, not a merchant's own.
 */
class DeadLetteredEventsApiIT extends PostgresSpringBootIT {

    @DynamicPropertySource
    static void mtnConfigThisTestNeverCalls(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.installations[0].base-url", () -> "http://localhost:1");
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "EUR");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "sandbox");
        registry.add("nkap.provider.default", () -> "mtn-sandbox");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApiKeyStore apiKeys;

    @Autowired
    Outbox outbox;

    @Autowired
    OutboxRelayStore store;

    @Autowired
    JdbcTemplate jdbc;

    private String adminKey;
    private String merchantKey;

    @BeforeEach
    void provisionKeys() throws Exception {
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "DeadLetteredEventsApiIT").token();
        merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "DeadLetteredEventsApiIT").token();
    }

    private UUID deadLetteredEvent(String merchantId, String eventType) {
        UUID id = UUID.randomUUID();
        outbox.append(new OutboxEvent(id, merchantId, eventType, "{\"id\":\"" + id + "\",\"type\":\"" + eventType + "\"}"));
        store.recordFailure(id, "boom: receiver refused the request", true, Instant.now());
        return id;
    }

    @Test
    @DisplayName("a dead-lettered event appears in the listing — refused to a merchant key, served to an admin one")
    void a_dead_lettered_event_appears_in_the_listing() throws Exception {
        String merchant = "merchant-dead-letter-" + System.nanoTime();
        UUID eventId = deadLetteredEvent(merchant, "payment.succeeded");

        assertThat(list(merchantKey).getStatusCode())
                .as("dead-lettered events are operator data across merchants, not a merchant's own")
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> response = list(adminKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode events = json.readTree(response.getBody());
        assertThat(events.isArray()).isTrue();
        boolean found = false;
        for (JsonNode event : events) {
            if (eventId.toString().equals(event.get("eventId").asText())) {
                found = true;
                assertThat(event.get("merchantId").asText()).isEqualTo(merchant);
                assertThat(event.get("eventType").asText()).isEqualTo("payment.succeeded");
                assertThat(event.get("lastError").asText()).isNotBlank();
                assertThat(event.get("deadLetteredAt").asText()).isNotBlank();
            }
        }
        assertThat(found).as("the dead-lettered event should be in the listing").isTrue();
    }

    @Test
    @DisplayName("an event still being retried does not appear in the listing — it is what has stopped, not what is late")
    void a_still_retrying_event_does_not_appear() throws Exception {
        String merchant = "merchant-still-retrying-" + System.nanoTime();
        UUID retryingId = UUID.randomUUID();
        outbox.append(new OutboxEvent(retryingId, merchant, "payment.succeeded",
                "{\"id\":\"" + retryingId + "\"}"));
        store.recordFailure(retryingId, "boom: receiver refused the request, will retry", false, Instant.now());
        UUID deadLetteredId = deadLetteredEvent(merchant, "payment.failed");

        try {
            ResponseEntity<String> response = list(adminKey);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode events = json.readTree(response.getBody());
            for (JsonNode event : events) {
                assertThat(event.get("eventId").asText())
                        .as("an event that is only retrying, not dead-lettered, should not be in this listing")
                        .isNotEqualTo(retryingId.toString());
            }
            assertThat(events).anySatisfy(event ->
                    assertThat(event.get("eventId").asText()).isEqualTo(deadLetteredId.toString()));
        } finally {
            // Every IT test shares one PostgreSQL instance for the life of the JVM, with no
            // per-class isolation of outbox_event. Left un-dead-lettered, this row stays
            // "due" — and pushing next_attempt_at into the future is an arms race against
            // clock jumps other tests make on their own terms (OutboxRelayIT deliberately
            // jumps a day ahead to prove a dead-lettered event is never claimed again, which
            // collided with an earlier version of this fix). Deleting it is the only version
            // of this that cannot collide with what any other test decides to do with time.
            jdbc.update("DELETE FROM outbox_event WHERE id = ?", retryingId);
        }
    }

    private ResponseEntity<String> list(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return http.exchange("/webhooks/events/dead-lettered", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
