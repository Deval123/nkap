package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * What {@code /actuator} exposes, and where. Two claims, not one, since the follow-up plan
 * for issue #75 turned this into two: the <strong>management</strong> port ({@code
 * management.server.port}, {@code application.yml}) serves exactly {@code health} and
 * {@code prometheus} — the same property this class asserted before, only relocated — and
 * the <strong>API</strong> port (published, the one the world can reach) serves neither. The
 * two are asserted separately, deliberately: a passing "the management port is right" test
 * would once have been the whole story, and the defect this file now also guards against is
 * exactly that gap — the API port answering {@code /actuator/**} because nobody thought to
 * check it didn't.
 *
 * <p>{@code AuthWebConfig} <strong>still</strong> excludes {@code /actuator/**} from
 * authentication, and this class is why that exclusion turned out to still be needed: the
 * management port's embedded server runs in a Spring context that is a <em>child</em> of
 * this one, and a child context inherits its parent's {@code WebMvcConfigurer} beans —
 * without the exclusion, health and metrics would both demand an API key on a port no
 * scraper or health check ever sends one to. That surprised the first draft of this port
 * separation, which assumed the exclusion had become dead code; it had not, only what it
 * protects had moved.
 */
class ActuatorSurfaceIT extends PostgresSpringBootIT {

    @DynamicPropertySource
    static void mtnConfigThisTestNeverCalls(DynamicPropertyRegistry registry) {
        // This test never reaches MTN; any non-null config satisfies the context (same
        // pattern as StatementApiIT).
        registry.add("nkap.provider.mtn.base-url", () -> "http://localhost:1");
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> "unused");
        registry.add("nkap.provider.mtn.api-user", () -> "unused");
        registry.add("nkap.provider.mtn.api-key", () -> "unused");
        registry.add("nkap.provider.mtn.currency", () -> "EUR");
        registry.add("nkap.provider.mtn.country", () -> "sandbox");
    }

    /** The API port — random per test run, resolved by TestRestTemplate the same way every other *ApiIT uses it. */
    @Autowired
    TestRestTemplate api;

    @Autowired
    ObjectMapper json;

    /**
     * {@code webEnvironment = RANDOM_PORT} randomises {@code management.server.port} to an
     * ephemeral value the same way it randomises {@code server.port} — application.yml's
     * fixed 9464 is not what actually gets bound in a test. Spring exposes the real one as
     * this property, the management-port counterpart of {@code local.server.port}
     * ({@code @LocalServerPort} wraps that one; there is no {@code @LocalManagementPort}, so
     * this reads the property directly).
     */
    @Value("${local.management.port}")
    private int managementPort;

    /**
     * A plain {@link RestTemplate}, not {@link TestRestTemplate}: the latter is wired to the
     * API port ({@code @LocalServerPort}) and there is no management-port equivalent to
     * autowire. Given a no-op error handler so a 4xx becomes an ordinary
     * {@link ResponseEntity} rather than a thrown exception — {@code TestRestTemplate}
     * behaves the same way by default, and a 404 is exactly what several of these tests
     * assert on.
     */
    private final RestTemplate management = nonThrowingRestTemplate();

    private static RestTemplate nonThrowingRestTemplate() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    // --- the API port: nothing here, not even gated -----------------------------------

    @Test
    @DisplayName("the API port does not serve /actuator/prometheus — not gated, not there")
    void the_api_port_does_not_serve_prometheus() {
        ResponseEntity<String> response = api.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the API port does not serve /actuator/health either — health moved with the rest")
    void the_api_port_does_not_serve_health() {
        ResponseEntity<String> response = api.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- the management port: exactly health and prometheus, unauthenticated ----------

    @Test
    @DisplayName("the management port's actuator index lists exactly health and prometheus — nothing added by accident")
    void the_index_lists_exactly_the_two_intended_endpoints() throws Exception {
        ResponseEntity<String> response = onManagementPort("/actuator", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode links = json.readTree(response.getBody()).get("_links");
        List<String> exposed = new ArrayList<>();
        links.fieldNames().forEachRemaining(exposed::add);

        assertThat(exposed).as("self is the index link, not an endpoint")
                .containsExactlyInAnyOrder("self", "health", "health-path", "prometheus");
    }

    @Test
    @DisplayName("GET /actuator/health on the management port needs no key — the compose file's health check carries none")
    void health_needs_no_key() {
        ResponseEntity<String> response = onManagementPort("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /actuator/prometheus on the management port needs no key — a Prometheus scrape carries none either")
    void prometheus_needs_no_key() {
        ResponseEntity<String> response = onManagementPort("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("nkap_suspense_balance");
    }

    @Test
    @DisplayName("every other actuator endpoint is absent on the management port too — not merely gated, not there at all")
    void every_other_actuator_endpoint_is_absent() {
        for (String path : List.of("env", "beans", "configprops", "mappings", "heapdump", "threaddump", "loggers")) {
            ResponseEntity<String> response = onManagementPort("/actuator/" + path, String.class);
            assertThat(response.getStatusCode())
                    .as("/actuator/%s must not be exposed", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private <T> ResponseEntity<T> onManagementPort(String path, Class<T> type) {
        return management.getForEntity("http://localhost:" + managementPort + path, type);
    }
}
