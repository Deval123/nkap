package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * The adjustment routes with the feature in its default state, off. All five answer
 * {@code 501} with {@code adjustment-not-enabled}: not {@code 404}, because the routes exist,
 * and not {@code feature-not-offered}, because a client has to be able to tell "this provider
 * cannot do it" from "the operator switched it off".
 *
 * <p>This class is also where {@code docs/openapi.yaml}'s {@code 501} on those five routes is
 * proved, because {@code OpenApiSpecIT} runs with the feature on to produce everything else.
 * The spec checks are the same two as there and in {@code FeatureNotOfferedApiIT}: the status is
 * documented, and every field {@code Problem} marks required is present.
 */
@SuppressWarnings("unchecked")
class AdjustmentApiIT extends PostgresSpringBootIT {

    private static Map<String, Object> spec;

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
        // nkap.adjustments.enabled is deliberately not set: the default is what is under test.
    }

    @BeforeAll
    static void loadSpec() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("..", "docs", "openapi.yaml"))) {
            spec = new Yaml().load(in);
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApiKeyStore apiKeys;

    private String merchantKey;

    @BeforeEach
    void setUp() {
        merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "AdjustmentApiIT").token();
    }

    private record Route(HttpMethod method, String concretePath, String specPath) {
    }

    private static List<Route> routes() {
        String id = UUID.randomUUID().toString();
        return List.of(
                new Route(HttpMethod.POST, "/adjustments", "/adjustments"),
                new Route(HttpMethod.GET, "/adjustments", "/adjustments"),
                new Route(HttpMethod.GET, "/adjustments/" + id, "/adjustments/{id}"),
                new Route(HttpMethod.POST, "/adjustments/" + id + "/approve", "/adjustments/{id}/approve"),
                new Route(HttpMethod.POST, "/adjustments/" + id + "/reject", "/adjustments/{id}/reject"));
    }

    private ResponseEntity<String> call(Route route, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return http.exchange(route.concretePath(), route.method(), new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("while adjustments are switched off, all five routes answer 501 adjustment-not-enabled, not 404, "
            + "and not the feature-not-offered type")
    void a_switched_off_feature_answers_501_with_its_own_type() throws Exception {
        for (Route route : routes()) {
            ResponseEntity<String> response = call(route, merchantKey, "{}");

            assertThat(response.getStatusCode()).as("%s %s", route.method(), route.concretePath())
                    .isEqualTo(HttpStatus.NOT_IMPLEMENTED);
            assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
            JsonNode problem = json.readTree(response.getBody());
            assertThat(problem.get("type").asText()).as(route.concretePath()).endsWith("/adjustment-not-enabled");
            assertThat(problem.get("type").asText()).doesNotEndWith("/feature-not-offered");
            assertThat(problem.get("detail").asText()).contains("nkap.adjustments.enabled");
        }
    }

    @Test
    @DisplayName("a switched-off feature refuses before it looks at the request: a bad id or an empty body is still 501")
    void it_refuses_before_validating_anything() throws Exception {
        ResponseEntity<String> badId = http.exchange("/adjustments/not-a-uuid", HttpMethod.GET,
                new HttpEntity<>(authorised()), String.class);
        ResponseEntity<String> noBody = http.exchange("/adjustments", HttpMethod.POST,
                new HttpEntity<>(authorised()), String.class);

        assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(noBody.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    @DisplayName("a request with no key is still 401 while the feature is off: authentication comes first")
    void authentication_comes_before_the_feature_gate() {
        for (Route route : routes()) {
            assertThat(call(route, null, "{}").getStatusCode()).as(route.concretePath())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // --- docs/openapi.yaml: the 501 OpenApiSpecIT's context cannot produce ----------------

    @Test
    @DisplayName("docs/openapi.yaml documents the 501 on each of the five routes, with the Problem fields present in the real answer")
    void the_spec_documents_the_501_on_every_route() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        List<String> required = (List<String>) ((Map<String, Object>) schemas.get("Problem")).get("required");

        for (Route route : routes()) {
            Map<String, Object> operation = (Map<String, Object>)
                    ((Map<String, Object>) paths.get(route.specPath())).get(route.method().name().toLowerCase(Locale.ROOT));
            Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
            assertThat(responses.keySet()).as("%s %s", route.method(), route.specPath()).contains("501");

            JsonNode problem = json.readTree(call(route, merchantKey, "{}").getBody());
            for (String field : required) {
                assertThat(problem.has(field)).as("'%s' on %s", field, route.concretePath()).isTrue();
            }
        }
    }

    private HttpHeaders authorised() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(merchantKey);
        return headers;
    }
}
