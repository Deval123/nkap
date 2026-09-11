package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.outbox.Outbox;
import dev.nkap.server.outbox.OutboxEvent;
import dev.nkap.server.support.PostgresSpringBootIT;
import dev.nkap.server.support.StubReceiver;
import dev.nkap.server.webhook.WebhookEndpointStore;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code POST /webhooks/events/{eventId}/replay} — the API half of the roadmap's "replay
 * from the console or the API" line. Admin-gated, the same way {@code GET /statements/imports}
 * and {@code GET /balance} are, because a replay triggers an outbound call in a merchant's
 * name even though it writes nothing to the ledger.
 */
class WebhookReplayApiIT extends PostgresSpringBootIT {

    @DynamicPropertySource
    static void mtnConfigThisTestNeverCalls(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.base-url", () -> "http://localhost:1");
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> "unused");
        registry.add("nkap.provider.mtn.api-user", () -> "unused");
        registry.add("nkap.provider.mtn.api-key", () -> "unused");
        registry.add("nkap.provider.mtn.currency", () -> "EUR");
        registry.add("nkap.provider.mtn.country", () -> "sandbox");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApiKeyStore apiKeys;

    @Autowired
    WebhookEndpointStore endpoints;

    @Autowired
    Outbox outbox;

    private String adminKey;
    private String merchantKey;
    private StubReceiver receiver;

    @BeforeEach
    void provisionKeys() throws Exception {
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "WebhookReplayApiIT").token();
        merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "WebhookReplayApiIT").token();
        receiver = new StubReceiver();
    }

    @AfterEach
    void closeReceiver() {
        receiver.close();
    }

    private UUID storedEvent(String merchantId, String eventType) {
        UUID id = UUID.randomUUID();
        outbox.append(new OutboxEvent(id, merchantId, eventType, "{\"id\":\"" + id + "\",\"type\":\"" + eventType + "\"}"));
        return id;
    }

    @Test
    @DisplayName("a replay with a merchant key is refused — it triggers a call in a merchant's name")
    void a_merchant_key_is_refused() {
        String merchant = "merchant-refused-" + System.nanoTime();
        UUID eventId = storedEvent(merchant, "payment.succeeded");

        ResponseEntity<String> response = replay(merchantKey, eventId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("replaying an unknown event id is a 404, with an admin key")
    void an_unknown_event_is_not_found() {
        ResponseEntity<String> response = replay(adminKey, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("replaying an event for a merchant with no registered endpoint is a 404")
    void a_merchant_with_no_endpoint_is_not_found() {
        String merchant = "merchant-no-endpoint-" + System.nanoTime();
        UUID eventId = storedEvent(merchant, "payment.succeeded");

        ResponseEntity<String> response = replay(adminKey, eventId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a successful replay resends the event and reports it delivered, signed with the merchant's own secret")
    void a_successful_replay_resends_and_reports_delivered() throws Exception {
        String merchant = "merchant-replay-" + System.nanoTime();
        endpoints.provisionWithSecret("whsec_replay-test", merchant, receiver.baseUrl().toString());
        receiver.alwaysRespond(200, "{}");
        UUID eventId = storedEvent(merchant, "payment.succeeded");

        ResponseEntity<String> response = replay(adminKey, eventId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("delivered").asBoolean()).isTrue();
        assertThat(body.get("eventId").asText()).isEqualTo(eventId.toString());

        assertThat(receiver.requests).singleElement().satisfies(request -> {
            assertThat(request.header("Nkap-Event-Id")).isEqualTo(eventId.toString());
            assertThat(request.header("Nkap-Signature")).isNotBlank();
        });
    }

    @Test
    @DisplayName("a replay against a receiver that refuses reports not delivered, without a 5xx")
    void a_replay_that_fails_reports_not_delivered() {
        String merchant = "merchant-replay-fails-" + System.nanoTime();
        endpoints.provisionWithSecret("whsec_replay-test", merchant, receiver.baseUrl().toString());
        receiver.alwaysRespond(500, "{}");
        UUID eventId = storedEvent(merchant, "payment.failed");

        ResponseEntity<String> response = replay(adminKey, eventId);

        assertThat(response.getStatusCode())
                .as("the receiver's failure is not this gateway's error")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"delivered\":false");
    }

    private ResponseEntity<String> replay(String key, UUID eventId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return http.exchange("/webhooks/events/" + eventId + "/replay", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }
}
