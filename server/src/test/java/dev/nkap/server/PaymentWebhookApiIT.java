package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.outbox.OutboxRelay;
import dev.nkap.server.support.BindLoopback;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import dev.nkap.server.support.StubReceiver;
import dev.nkap.server.webhook.WebhookEndpointStore;
import dev.nkap.testsupport.LoopbackOnly;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Issue #177: {@code cause} on the payload a merchant's webhook receiver actually gets, not
 * on {@code PaymentEventPayload} in isolation — {@code OutboxNotifierTest} already pins the
 * builder's own logic; this pins the whole chain from {@code POST /payments} through the
 * real {@link OutboxRelay} to a real (if throwaway) HTTP receiver.
 *
 * <p>Not a {@code PostgresSpringBootIT}: that base sets {@code nkap.webhooks.enabled=false},
 * and delivering to {@link StubReceiver} is the entire point here. {@code
 * DisbursementApiIT}'s own javadoc explains why a scheduled pass being on needs a long
 * interval and {@code @DirtiesContext} rather than being left to race a manual
 * {@link OutboxRelay#runOnce()} — the same reasoning applies to this class's own context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(DockerAvailable.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentWebhookApiIT {

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        // Not a PostgresSpringBootIT (see the class javadoc), so the base's loopback binding is
        // registered here as well.
        BindLoopback.register(registry);
        PostgresDatabase db = PostgresDatabase.shared();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");

        // Collections only, on purpose: DISBURSE against this installation is exactly the
        // ADR 0013 gateway refusal PaymentApiIT's own test drives, never reaching MTN or the
        // simulator here either.
        registry.add("nkap.provider.mtn.installations[0].base-url", () -> "http://localhost:1");
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "EUR");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "sandbox");
        registry.add("nkap.provider.default", () -> "mtn-sandbox");

        registry.add("nkap.reconciler.enabled", () -> "false");

        // The relay itself stays on (application.yml's own default), but its scheduled pass
        // must not race the manual runOnce() below -- a long interval, the same fix
        // DisbursementApiIT uses for the reconciler. StubReceiver is plain HTTP.
        registry.add("nkap.webhooks.interval", () -> "PT1H");
        registry.add("nkap.webhooks.allow-insecure-endpoint-url", () -> "true");
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
    OutboxRelay relay;

    private String apiKey;
    private String merchantId;
    private StubReceiver receiver;

    @BeforeEach
    void setUp() throws Exception {
        // relay.runOnce() claims every due event in the table, not only this class's own
        // (issue #206).
        PostgresDatabase.shared().setAsidePendingOutboxEvents();
        merchantId = "merchant-" + System.nanoTime();
        apiKey = apiKeys.provision(merchantId, false, "PaymentWebhookApiIT").token();
        receiver = new StubReceiver();
        receiver.alwaysRespond(200, "{}");
    }

    @AfterEach
    void closeReceiver() {
        receiver.close();
    }

    @LocalServerPort
    private int port;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    @DisplayName("this context's gateway binds 127.0.0.1 alone, on its API and management ports (issue #221)")
    void this_context_binds_loopback_only() throws Exception {
        LoopbackOnly.assertBoundToLoopbackOnly(port);
        LoopbackOnly.assertBoundToLoopbackOnly(managementPort);
    }

    @Test
    @DisplayName("a DISBURSE request against a Collections-only installation delivers a payment.failed webhook whose cause is GATEWAY")
    void a_gateway_refusal_delivers_a_webhook_carrying_gateway_as_the_cause() throws Exception {
        endpoints.provisionWithSecret("whsec_test", merchantId, receiver.baseUrl().toString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = """
            {"operation":"DISBURSE","amount":5000,"currency":"EUR","country":"sandbox",
             "counterpartyMsisdn":"46733123453","payerMessage":"payout","payeeNote":"payout"}""";

        ResponseEntity<String> created = http.postForEntity("/payments", new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = json.readTree(created.getBody());
        assertThat(payment.get("state").asText()).isEqualTo("FAILED");
        String reference = payment.get("reference").asText();

        relay.runOnce();

        assertThat(receiver.requests).singleElement().satisfies(request -> {
            JsonNode delivered = readJson(request.body());
            assertThat(delivered.get("type").asText()).isEqualTo("payment.failed");
            assertThat(delivered.get("reference").asText()).isEqualTo(reference);
            assertThat(delivered.get("state").asText()).isEqualTo("FAILED");
            assertThat(delivered.get("cause").asText())
                    .as("the operator was never asked -- this must not read as an operator refusal")
                    .isEqualTo("GATEWAY");
            assertThat(delivered.get("providerCode").asText()).isEmpty();
        });
    }

    private JsonNode readJson(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("delivered body was not JSON: " + body, e);
        }
    }
}
