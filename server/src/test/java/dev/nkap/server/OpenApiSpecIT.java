package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.outbox.Outbox;
import dev.nkap.server.outbox.OutboxEvent;
import dev.nkap.server.outbox.OutboxRelayStore;
import dev.nkap.server.statement.ReconciliationReport;
import dev.nkap.server.statement.StatementImport;
import dev.nkap.server.support.PostgresSpringBootIT;
import dev.nkap.server.webhook.WebhookEndpointStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #88's own reason for existing: {@code docs/openapi.yaml} is a file in the
 * repository, not generated, and this is the check that keeps it true rather than merely
 * hoped. Two things, both against the same running application {@code PostgresSpringBootIT}
 * already starts:
 *
 * <ol>
 *   <li>{@link #the_spec_documents_exactly_the_real_http_surface()} — every path and method
 *       the spec declares is a real Spring MVC mapping, and every real mapping is
 *       documented. Neither direction is optional: a stale path in the spec is exactly as
 *       wrong as an endpoint nobody wrote down.</li>
 *   <li>One test per documented status code, actually producing it and checking every field
 *       the matching schema marks {@code required} is present in the real response. A
 *       schema's shape is asserted by reading {@code docs/openapi.yaml} itself
 *       ({@link #requiredFields}) rather than duplicated by hand here, so a field added to
 *       one and not the other fails loudly instead of the two quietly drifting apart.</li>
 * </ol>
 *
 * <p>One exception to "against the same running application": the {@code 501} that
 * {@code GET /balance} and {@code GET /account-holders/{msisdn}} document is proved in
 * {@code FeatureNotOfferedApiIT}, with the same two checks. It needs a default provider that
 * declares neither feature, {@code nkap.provider.default} is context-wide, and this context's
 * default has to declare both to produce its {@code 200}s. A status that cannot be produced
 * here is proved in a context that can produce it, never left undocumented or unproved.
 *
 * <p>Parsed with SnakeYAML, already on the classpath via {@code spring-boot-starter} — see
 * {@code nkap-standalone.compose.yaml}'s own reasoning (issue #86) for why that beats adding
 * a dependency for this alone.
 */
@SuppressWarnings("unchecked")
class OpenApiSpecIT extends PostgresSpringBootIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();
    private static Map<String, Object> spec;

    @DynamicPropertySource
    static void mtnPointsAtTheSimulator(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.installations[0].base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "test-subscription-key");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "test-api-user");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "test-api-key");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "EUR");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].request-timeout", () -> "PT2S");
        registry.add("nkap.provider.mtn.installations[0].disbursement.subscription-key", () -> "disbursement-sub-key");
        registry.add("nkap.provider.mtn.installations[0].disbursement.api-user", () -> "disbursement-user");
        registry.add("nkap.provider.mtn.installations[0].disbursement.api-key", () -> "disbursement-key");
        registry.add("nkap.provider.default", () -> "mtn-sandbox");
        registry.add("nkap.webhooks.allow-insecure-endpoint-url", () -> "true");
    }

    @BeforeAll
    static void loadSpec() throws IOException {
        // Surefire/Failsafe run with the module directory (server/) as the working
        // directory; the spec lives one level up, in the repository root's docs/.
        Path path = Path.of("..", "docs", "openapi.yaml");
        try (InputStream in = Files.newInputStream(path)) {
            spec = new Yaml().load(in);
        }
    }

    @AfterAll
    static void stopSimulator() {
        SIMULATOR.close();
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApiKeyStore apiKeys;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    StatementImport statementImport;

    @Autowired
    Outbox outbox;

    @Autowired
    OutboxRelayStore relayStore;

    @Autowired
    WebhookEndpointStore endpoints;

    private String merchantKey;
    private String adminKey;

    @BeforeEach
    void setUp() {
        SIMULATOR.reset();
        merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "OpenApiSpecIT").token();
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "OpenApiSpecIT").token();
    }

    // --- 1. the spec's HTTP surface is exactly the real one, both directions ------------

    @Test
    @DisplayName("docs/openapi.yaml documents exactly the paths and methods this application actually maps -- nothing missing, nothing stale")
    void the_spec_documents_exactly_the_real_http_surface() {
        assertThat(specPathMethods()).isEqualTo(actualPathMethods());
    }

    private Set<String> specPathMethods() {
        Set<String> result = new TreeSet<>();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            Map<String, Object> operations = (Map<String, Object>) entry.getValue();
            for (String method : operations.keySet()) {
                result.add(method.toUpperCase(Locale.ROOT) + " " + entry.getKey());
            }
        }
        return result;
    }

    private Set<String> actualPathMethods() {
        Set<String> result = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            var patternsCondition = info.getPathPatternsCondition();
            if (patternsCondition == null) {
                continue;
            }
            for (PathPattern pattern : patternsCondition.getPatterns()) {
                String patternString = pattern.getPatternString();
                if (patternString.equals("/error")) {
                    // Boot's BasicErrorController, the container's own forward -- excluded
                    // from CallerAuthInterceptor for the same reason (AuthWebConfig).
                    continue;
                }
                for (var httpMethod : info.getMethodsCondition().getMethods()) {
                    result.add(httpMethod.name() + " " + patternString);
                }
            }
        }
        return result;
    }

    // --- 2. every documented status code, produced for real, required fields checked ----

    /** {@code components.schemas.<name>.required}, straight from the spec -- never duplicated by hand. */
    private List<String> requiredFields(String schemaName) {
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaName);
        return (List<String>) schema.getOrDefault("required", List.of());
    }

    private void assertRequired(JsonNode node, String schemaName) {
        for (String field : requiredFields(schemaName)) {
            assertThat(node.has(field))
                    .as("'%s' is required by components.schemas.%s but missing from: %s", field, schemaName, node)
                    .isTrue();
        }
    }

    /** {@code paths.<path>.<method>.responses} names {@code status} as a documented code. */
    private void assertStatusDocumented(String path, String method, int status) {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get(method);
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertThat(responses.keySet())
                .as("docs/openapi.yaml's %s %s does not document status %d", method.toUpperCase(Locale.ROOT), path, status)
                .contains(String.valueOf(status));
    }

    private JsonNode body(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + response.getBody(), e);
        }
    }

    private void check(String path, String method, int status, ResponseEntity<String> response, String schemaName) {
        assertThat(response.getStatusCode().value()).as("%s %s", method, path).isEqualTo(status);
        assertStatusDocumented(path, method, status);
        if (schemaName != null) {
            assertRequired(body(response), schemaName);
        }
    }

    // --- POST /payments -------------------------------------------------------------

    private ResponseEntity<String> postPayment(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(merchantKey);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.postForEntity("/payments", new HttpEntity<>(body, headers), String.class);
    }

    private static String paymentBody(long amount) {
        return """
            {"operation":"COLLECT","amount":%d,"currency":"EUR","country":"sandbox",
             "counterpartyMsisdn":"46733123453","payerMessage":"rent","payeeNote":"march"}"""
                .formatted(amount);
    }

    @Test
    @DisplayName("POST /payments: 201, the operator answered")
    void post_payments_201() {
        check("/payments", "post", 201, postPayment(UUID.randomUUID().toString(), paymentBody(5000)), "PaymentResponse");
    }

    @Test
    @DisplayName("POST /payments: 202, the operator did not answer")
    void post_payments_202() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        check("/payments", "post", 202, postPayment(UUID.randomUUID().toString(), paymentBody(5000)), "PaymentResponse");
    }

    @Test
    @DisplayName("POST /payments: 400, missing Idempotency-Key")
    void post_payments_400() {
        check("/payments", "post", 400, postPayment(null, paymentBody(5000)), "Problem");
    }

    @Test
    @DisplayName("POST /payments: 401, no key")
    void post_payments_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> response = http.postForEntity("/payments", new HttpEntity<>(paymentBody(5000), headers), String.class);
        check("/payments", "post", 401, response, "Problem");
    }

    @Test
    @DisplayName("POST /payments: 409, the same key with a different body")
    void post_payments_409() {
        String key = UUID.randomUUID().toString();
        postPayment(key, paymentBody(5000));
        check("/payments", "post", 409, postPayment(key, paymentBody(9000)), "Problem");
    }

    // --- GET /payments/{reference} ---------------------------------------------------

    private ResponseEntity<String> getPayment(String reference, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return http.exchange("/payments/" + reference, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("GET /payments/{reference}: 200")
    void get_payment_200() {
        String reference = body(postPayment(UUID.randomUUID().toString(), paymentBody(5000))).get("reference").asText();
        check("/payments/{reference}", "get", 200, getPayment(reference, merchantKey), "PaymentResponse");
    }

    @Test
    @DisplayName("GET /payments/{reference}: 400, not a well-formed reference")
    void get_payment_400() {
        check("/payments/{reference}", "get", 400, getPayment("not-a-reference", merchantKey), "Problem");
    }

    @Test
    @DisplayName("GET /payments/{reference}: 401, no key")
    void get_payment_401() {
        check("/payments/{reference}", "get", 401, getPayment(UUID.randomUUID().toString(), null), "Problem");
    }

    @Test
    @DisplayName("GET /payments/{reference}: 404, no such payment")
    void get_payment_404() {
        check("/payments/{reference}", "get", 404, getPayment(UUID.randomUUID().toString(), merchantKey), "Problem");
    }

    // --- POST /payments/{reference}/refunds ------------------------------------------

    private ResponseEntity<String> postRefund(String reference, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(merchantKey);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.postForEntity(
                "/payments/" + reference + "/refunds", new HttpEntity<>(body == null ? "{}" : body, headers), String.class);
    }

    private String settledCollection(long amount) {
        String reference = body(postPayment(UUID.randomUUID().toString(), paymentBody(amount))).get("reference").asText();
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        // The happy path already leaves it SUBMITTED; a callback confirms it SUCCEEDED, the
        // same path CallbackApiIT drives -- reused here rather than waiting on the reconciler.
        http.postForEntity("/callbacks/mtn-sandbox",
                new HttpEntity<>("{\"referenceId\":\"" + reference + "\",\"status\":\"SUCCESSFUL\"}"), Void.class);
        return reference;
    }

    @Test
    @DisplayName("POST /payments/{reference}/refunds: 201")
    void post_refund_201() {
        String original = settledCollection(5000);
        check("/payments/{reference}/refunds", "post", 201,
                postRefund(original, UUID.randomUUID().toString(), null), "PaymentResponse");
    }

    @Test
    @DisplayName("POST /payments/{reference}/refunds: 202, the operator did not answer")
    void post_refund_202() {
        String original = settledCollection(5000);
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        check("/payments/{reference}/refunds", "post", 202,
                postRefund(original, UUID.randomUUID().toString(), null), "PaymentResponse");
    }

    @Test
    @DisplayName("POST /payments/{reference}/refunds: 400, exceeds what remains")
    void post_refund_400() {
        String original = settledCollection(5000);
        check("/payments/{reference}/refunds", "post", 400,
                postRefund(original, UUID.randomUUID().toString(), "{\"amount\":5001}"), "Problem");
    }

    @Test
    @DisplayName("POST /payments/{reference}/refunds: 401, no key")
    void post_refund_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> response = http.postForEntity(
                "/payments/" + UUID.randomUUID() + "/refunds", new HttpEntity<>("{}", headers), String.class);
        check("/payments/{reference}/refunds", "post", 401, response, "Problem");
    }

    @Test
    @DisplayName("POST /payments/{reference}/refunds: 404, no such collection")
    void post_refund_404() {
        check("/payments/{reference}/refunds", "post", 404,
                postRefund(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null), "Problem");
    }

    @Test
    @DisplayName("POST /payments/{reference}/refunds: 409, the same key with a different body")
    void post_refund_409() {
        String original = settledCollection(5000);
        String key = UUID.randomUUID().toString();
        postRefund(original, key, "{\"amount\":1000}");
        check("/payments/{reference}/refunds", "post", 409, postRefund(original, key, "{\"amount\":2000}"), "Problem");
    }

    // --- GET /balance -----------------------------------------------------------------

    // 501 is proved in FeatureNotOfferedApiIT -- see the class javadoc for why not here.

    private ResponseEntity<String> getBalance(String key, String operation, String currency) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.setBearerAuth(key);
        }
        String query = "?operation=" + operation + "&currency=" + currency;
        return http.exchange("/balance" + query, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("GET /balance: 200")
    void get_balance_200() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"ACCEPT"}}}]}""");
        check("/balance", "get", 200, getBalance(adminKey, "COLLECT", "EUR"), "BalanceResponse");
    }

    @Test
    @DisplayName("GET /balance: 400, not a real operation")
    void get_balance_400() {
        check("/balance", "get", 400, getBalance(adminKey, "not-an-operation", "EUR"), "Problem");
    }

    @Test
    @DisplayName("GET /balance: 401, no key")
    void get_balance_401() {
        check("/balance", "get", 401, getBalance(null, "COLLECT", "EUR"), "Problem");
    }

    @Test
    @DisplayName("GET /balance: 403, a merchant key")
    void get_balance_403() {
        check("/balance", "get", 403, getBalance(merchantKey, "COLLECT", "EUR"), "Problem");
    }

    @Test
    @DisplayName("GET /balance: 503, the operator does not answer")
    void get_balance_503() {
        // The balance and account-holder reads have no reference and no timeline (ADR 0002
        // does not apply) -- they are scripted through the declaration's own top-level
        // "account" field, not a scenario rule's onSubmit/onQuery.
        SIMULATOR.declareScenario("""
            {"account":{"balance":{"outcome":"NO_RESPONSE"}}}""");
        check("/balance", "get", 503, getBalance(adminKey, "COLLECT", "EUR"), "Problem");
    }

    // --- GET /account-holders/{msisdn} -------------------------------------------------

    private ResponseEntity<String> getAccountHolder(String key, String operation) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return http.exchange("/account-holders/46733123453?operation=" + operation,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn}: 200")
    void get_account_holder_200() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"ACCEPT"}}}]}""");
        check("/account-holders/{msisdn}", "get", 200, getAccountHolder(merchantKey, "COLLECT"), "AccountHolderResponse");
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn}: 400, not a real operation")
    void get_account_holder_400() {
        check("/account-holders/{msisdn}", "get", 400, getAccountHolder(merchantKey, "not-an-operation"), "Problem");
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn}: 401, no key")
    void get_account_holder_401() {
        check("/account-holders/{msisdn}", "get", 401, getAccountHolder(null, "COLLECT"), "Problem");
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn}: 503, the operator does not answer")
    void get_account_holder_503() {
        SIMULATOR.declareScenario("""
            {"account":{"holder":{"outcome":"NO_RESPONSE"}}}""");
        check("/account-holders/{msisdn}", "get", 503, getAccountHolder(merchantKey, "COLLECT"), "Problem");
    }

    // --- GET /statements/imports/{importId} --------------------------------------------

    private ResponseEntity<String> getStatementImport(String importId, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return http.exchange("/statements/imports/" + importId, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("GET /statements/imports/{importId}: 200")
    void get_statement_import_200() throws Exception {
        Path file = Files.createTempFile("openapi-spec-it-", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
                + "orphan-" + System.nanoTime() + ",2500,0,EUR,2026-09-10T14:00:00Z,SETTLED\n");
        ReconciliationReport report = statementImport.run(file, ProviderId.of("mtn-sandbox"), "openapi-spec-it.csv").report();

        check("/statements/imports/{importId}", "get", 200,
                getStatementImport(report.importId().toString(), adminKey), "ReconciliationReport");
    }

    @Test
    @DisplayName("GET /statements/imports/{importId}: 401, no key")
    void get_statement_import_401() {
        check("/statements/imports/{importId}", "get", 401, getStatementImport(UUID.randomUUID().toString(), null), "Problem");
    }

    @Test
    @DisplayName("GET /statements/imports/{importId}: 403, a merchant key")
    void get_statement_import_403() {
        check("/statements/imports/{importId}", "get", 403,
                getStatementImport(UUID.randomUUID().toString(), merchantKey), "Problem");
    }

    @Test
    @DisplayName("GET /statements/imports/{importId}: 404, no such import")
    void get_statement_import_404() {
        check("/statements/imports/{importId}", "get", 404, getStatementImport(UUID.randomUUID().toString(), adminKey), "Problem");
    }

    // --- GET /webhooks/events/dead-lettered --------------------------------------------

    private ResponseEntity<String> getDeadLettered(String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return http.exchange("/webhooks/events/dead-lettered", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("GET /webhooks/events/dead-lettered: 200, with one real dead-lettered event")
    void get_dead_lettered_200() {
        UUID eventId = UUID.randomUUID();
        outbox.append(new OutboxEvent(eventId, "merchant-x", "payment.succeeded",
                "{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\"}"));
        relayStore.recordFailure(eventId, "boom: receiver refused the request", true, Instant.now());

        ResponseEntity<String> response = getDeadLettered(adminKey);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertStatusDocumented("/webhooks/events/dead-lettered", "get", 200);
        JsonNode events = body(response);
        assertThat(events.isArray()).isTrue();
        boolean found = false;
        for (JsonNode event : events) {
            if (eventId.toString().equals(event.path("eventId").asText())) {
                found = true;
                assertRequired(event, "DeadLetteredEventResponse");
            }
        }
        assertThat(found).as("the event just dead-lettered should be in the listing").isTrue();
    }

    @Test
    @DisplayName("GET /webhooks/events/dead-lettered: 401, no key")
    void get_dead_lettered_401() {
        check("/webhooks/events/dead-lettered", "get", 401, getDeadLettered(null), "Problem");
    }

    @Test
    @DisplayName("GET /webhooks/events/dead-lettered: 403, a merchant key")
    void get_dead_lettered_403() {
        check("/webhooks/events/dead-lettered", "get", 403, getDeadLettered(merchantKey), "Problem");
    }

    // --- POST /webhooks/events/{eventId}/replay ----------------------------------------

    private ResponseEntity<String> postReplay(String eventId, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return http.postForEntity("/webhooks/events/" + eventId + "/replay", new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("POST /webhooks/events/{eventId}/replay: 200, delivery attempted (and reported, whether or not it landed)")
    void post_replay_200() {
        String merchant = "merchant-replay-" + System.nanoTime();
        UUID eventId = UUID.randomUUID();
        outbox.append(new OutboxEvent(eventId, merchant, "payment.succeeded",
                "{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\"}"));
        // No live receiver needed to prove the response shape: an endpoint that will not
        // answer still gets a 200 with delivered=false, which is exactly
        // WebhookReplayResponse's own point -- a failed delivery is not this endpoint's
        // error. It still has to exist, though, or this is 404 no-webhook-endpoint instead.
        endpoints.provisionWithSecret("whsec_test", merchant, "https://127.0.0.1:1/unreachable");

        check("/webhooks/events/{eventId}/replay", "post", 200, postReplay(eventId.toString(), adminKey), "WebhookReplayResponse");
    }

    @Test
    @DisplayName("POST /webhooks/events/{eventId}/replay: 401, no key")
    void post_replay_401() {
        check("/webhooks/events/{eventId}/replay", "post", 401, postReplay(UUID.randomUUID().toString(), null), "Problem");
    }

    @Test
    @DisplayName("POST /webhooks/events/{eventId}/replay: 403, a merchant key")
    void post_replay_403() {
        check("/webhooks/events/{eventId}/replay", "post", 403, postReplay(UUID.randomUUID().toString(), merchantKey), "Problem");
    }

    @Test
    @DisplayName("POST /webhooks/events/{eventId}/replay: 404, no such event")
    void post_replay_404() {
        check("/webhooks/events/{eventId}/replay", "post", 404, postReplay(UUID.randomUUID().toString(), adminKey), "Problem");
    }

    // --- POST /callbacks/{providerId} --------------------------------------------------

    @Test
    @DisplayName("POST /callbacks/{providerId}: 202, even for a reference this gateway never issued")
    void post_callback_202() {
        ResponseEntity<Void> response = http.postForEntity("/callbacks/mtn-sandbox",
                new HttpEntity<>("{\"referenceId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESSFUL\"}"), Void.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertStatusDocumented("/callbacks/{providerId}", "post", 202);
    }

    @Test
    @DisplayName("POST /callbacks/{providerId}: 400, not a well-formed callback for this provider")
    void post_callback_400() {
        check("/callbacks/{providerId}", "post", 400,
                http.postForEntity("/callbacks/mtn-sandbox", new HttpEntity<>("not json"), String.class), "Problem");
    }

    @Test
    @DisplayName("POST /callbacks/{providerId}: 404, no such provider")
    void post_callback_404() {
        check("/callbacks/{providerId}", "post", 404,
                http.postForEntity("/callbacks/no-such-provider", new HttpEntity<>("{}"), String.class), "Problem");
    }

    // --- POST /callbacks/{providerId}/{reference} ------------------------------------

    // MTN's own parseCallback always finds a reference in referenceId/externalId or rejects
    // the body outright (400) -- it never returns CallbackEvent.unattributed, so a real MTN
    // callback here is always attributed by its body, never by falling back to the path
    // (see CallbackControllerTest for that branch, exercised against a mocked adapter). What
    // these two prove instead is that the path segment plays no part when the body already
    // names a reference, and does not break routing either way.

    @Test
    @DisplayName("POST /callbacks/{providerId}/{reference}: 202, even for a reference this gateway never issued")
    void post_callback_with_reference_202() {
        ResponseEntity<Void> response = http.postForEntity("/callbacks/mtn-sandbox/" + UUID.randomUUID(),
                new HttpEntity<>("{\"referenceId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESSFUL\"}"), Void.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertStatusDocumented("/callbacks/{providerId}/{reference}", "post", 202);
    }

    @Test
    @DisplayName("POST /callbacks/{providerId}/{reference}: 202, even when the path segment is not a well-formed reference")
    void post_callback_with_malformed_path_reference_202() {
        ResponseEntity<Void> response = http.postForEntity("/callbacks/mtn-sandbox/not-a-well-formed-reference",
                new HttpEntity<>("{\"referenceId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESSFUL\"}"), Void.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertStatusDocumented("/callbacks/{providerId}/{reference}", "post", 202);
    }

    @Test
    @DisplayName("POST /callbacks/{providerId}/{reference}: 400, not a well-formed callback for this provider")
    void post_callback_with_reference_400() {
        check("/callbacks/{providerId}/{reference}", "post", 400,
                http.postForEntity("/callbacks/mtn-sandbox/" + UUID.randomUUID(),
                        new HttpEntity<>("not json"), String.class), "Problem");
    }

    @Test
    @DisplayName("POST /callbacks/{providerId}/{reference}: 404, no such provider")
    void post_callback_with_reference_404() {
        check("/callbacks/{providerId}/{reference}", "post", 404,
                http.postForEntity("/callbacks/no-such-provider/" + UUID.randomUUID(),
                        new HttpEntity<>("{}"), String.class), "Problem");
    }

    @Test
    @DisplayName("POST /callbacks/{providerId}/{reference}: settles the payment, end to end through a real MTN adapter")
    void post_callback_with_reference_settles() {
        String reference = body(postPayment(UUID.randomUUID().toString(), paymentBody(5000))).get("reference").asText();
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");

        ResponseEntity<Void> response = http.postForEntity("/callbacks/mtn-sandbox/" + reference,
                new HttpEntity<>("{\"referenceId\":\"" + reference + "\",\"status\":\"SUCCESSFUL\"}"), Void.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(getPayment(reference, merchantKey).getBody()).contains("\"state\":\"SUCCEEDED\"");
    }
}
