package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.support.PostgresSpringBootIT;
import dev.nkap.server.support.StubReceiver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * An M-Pesa-only deployment: its default provider declares neither {@code BALANCE} nor
 * {@code HOLDER_VALIDATION}, so {@code GET /balance} and {@code GET /account-holders/{msisdn}}
 * answer {@code 501 feature-not-offered} — and the operator is never asked. Daraja is a stub
 * that records every request it receives, token calls included, so "never asked" is read off
 * the operator's side rather than inferred from the answer.
 *
 * <p>This class is also where {@code docs/openapi.yaml}'s {@code 501} on those two routes is
 * proved, because {@code OpenApiSpecIT} cannot prove it. {@code nkap.provider.default} is
 * context-wide, and that test's single context needs a default that declares both features to
 * produce its {@code 200}s; a default that declares neither needs a context of its own. That
 * is the cost of documenting this status, paid once here: one more Spring context, the one the
 * behaviour tests below need anyway. The spec checks read the same file the same way
 * {@code OpenApiSpecIT} does — a status is documented, and every field {@code Problem} marks
 * required is present — duplicated rather than shared, the same call {@link EmbeddedSimulator}
 * makes about two consumers.
 */
@SuppressWarnings("unchecked")
class FeatureNotOfferedApiIT extends PostgresSpringBootIT {

    private static final StubReceiver DARAJA = startStub();
    private static Map<String, Object> spec;

    private static StubReceiver startStub() {
        try {
            return new StubReceiver();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void mpesaOnly(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mpesa.installations[0].base-url", () -> DARAJA.baseUrl().resolve("/").toString());
        registry.add("nkap.provider.mpesa.installations[0].business-short-code", () -> "174379");
        registry.add("nkap.provider.mpesa.installations[0].passkey", () -> "test-passkey");
        registry.add("nkap.provider.mpesa.installations[0].consumer-key", () -> "test-consumer-key");
        registry.add("nkap.provider.mpesa.installations[0].consumer-secret", () -> "test-consumer-secret");
        registry.add("nkap.provider.mpesa.installations[0].currency", () -> "KES");
        registry.add("nkap.provider.mpesa.installations[0].country", () -> "ke");
        registry.add("nkap.provider.mpesa.installations[0].request-timeout", () -> "PT2S");
        registry.add("nkap.provider.default", () -> "mpesa-ke");
        registry.add("nkap.public-base-url", () -> "https://gateway.example.com");
    }

    @BeforeAll
    static void loadSpec() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("..", "docs", "openapi.yaml"))) {
            spec = new Yaml().load(in);
        }
    }

    @AfterAll
    static void stopStub() {
        DARAJA.close();
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApiKeyStore apiKeys;

    private String adminKey;

    @BeforeEach
    void setUp() {
        DARAJA.requests.clear();
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "FeatureNotOfferedApiIT").token();
    }

    @Test
    @DisplayName("GET /balance against a default that does not declare BALANCE is 501 feature-not-offered, and no request reaches the operator")
    void balance_is_not_offered_and_the_operator_is_not_asked() throws Exception {
        ResponseEntity<String> response = get("/balance?operation=COLLECT&currency=KES");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.get("type").asText()).endsWith("/feature-not-offered");
        assertThat(problem.get("detail").asText()).contains("BALANCE").contains("mpesa-ke").contains("[COLLECT]");
        assertThat(DARAJA.requests).as("the operator was contacted").isEmpty();
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn} against a default that does not declare HOLDER_VALIDATION is 501 feature-not-offered, and no request reaches the operator")
    void holder_validation_is_not_offered_and_the_operator_is_not_asked() throws Exception {
        ResponseEntity<String> response = get("/account-holders/254708374149?operation=COLLECT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.get("type").asText()).endsWith("/feature-not-offered");
        assertThat(problem.get("detail").asText()).contains("HOLDER_VALIDATION").contains("mpesa-ke");
        assertThat(DARAJA.requests).as("the operator was contacted").isEmpty();
    }

    @Test
    @DisplayName("the missing feature is refused before the operation is looked at: DISBURSE, which M-Pesa does not serve either, is still 501")
    void the_feature_is_refused_before_the_operation() throws Exception {
        ResponseEntity<String> response = get("/balance?operation=DISBURSE&currency=KES");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(json.readTree(response.getBody()).get("type").asText()).endsWith("/feature-not-offered");
    }

    // --- docs/openapi.yaml: the 501 OpenApiSpecIT's context cannot produce ----------------

    @Test
    @DisplayName("GET /balance: 501, the default provider does not offer a balance -- documented, with every required Problem field")
    void get_balance_501_is_documented() throws Exception {
        checkDocumented("/balance", 501, get("/balance?operation=COLLECT&currency=KES"));
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn}: 501, the default provider does not offer holder validation -- documented, with every required Problem field")
    void get_account_holder_501_is_documented() throws Exception {
        checkDocumented("/account-holders/{msisdn}", 501, get("/account-holders/254708374149?operation=COLLECT"));
    }

    private void checkDocumented(String path, int status, ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("get");
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertThat(responses.keySet())
                .as("docs/openapi.yaml's GET %s does not document status %d", path, status)
                .contains(String.valueOf(status));

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        List<String> required = (List<String>) ((Map<String, Object>) schemas.get("Problem")).getOrDefault("required", List.of());
        JsonNode body = json.readTree(response.getBody());
        for (String field : required) {
            assertThat(body.has(field))
                    .as("'%s' is required by components.schemas.Problem but missing from: %s", field, body)
                    .isTrue();
        }
    }

    private ResponseEntity<String> get(String pathAndQuery) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminKey);
        return http.exchange(pathAndQuery, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
