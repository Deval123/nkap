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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What {@code /actuator} exposes, held to exactly two: {@code health}, which predates this
 * issue and is what the compose file's health check polls, and {@code prometheus}, added by
 * issue #75. {@link AuthWebConfig} excludes {@code /actuator/**} from authentication
 * wholesale, on the strength of that being a short, low-risk, named list — the risk this
 * test defends against is that list growing by accident, wildcard-style
 * ({@code include: "*"}, say), which would publish {@code env}, {@code beans},
 * {@code heapdump} and the rest to anyone, unauthenticated, without anyone deciding that on
 * purpose.
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

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("the actuator index lists exactly health and prometheus — nothing added by accident")
    void the_index_lists_exactly_the_two_intended_endpoints() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/actuator", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode links = json.readTree(response.getBody()).get("_links");
        List<String> exposed = new ArrayList<>();
        links.fieldNames().forEachRemaining(exposed::add);

        assertThat(exposed).as("self is the index link, not an endpoint")
                .containsExactlyInAnyOrder("self", "health", "health-path", "prometheus");
    }

    @Test
    @DisplayName("GET /actuator/health needs no key — the compose file's health check carries none")
    void health_needs_no_key() {
        ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /actuator/prometheus needs no key — a Prometheus scrape carries none either")
    void prometheus_needs_no_key() {
        ResponseEntity<String> response = http.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("nkap_suspense_balance");
    }

    @Test
    @DisplayName("every other actuator endpoint is absent — not merely gated, not there at all")
    void every_other_actuator_endpoint_is_absent() {
        for (String path : List.of("env", "beans", "configprops", "mappings", "heapdump", "threaddump", "loggers")) {
            ResponseEntity<String> response = http.getForEntity("/actuator/" + path, String.class);
            assertThat(response.getStatusCode())
                    .as("/actuator/%s must not be exposed", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
