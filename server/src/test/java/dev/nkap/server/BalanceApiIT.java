package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.support.PostgresSpringBootIT;
import org.junit.jupiter.api.AfterAll;
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
 * {@code GET /balance} end to end: a real server, a real MTN adapter, a real simulator over
 * HTTP. Admin-gated, and a live read — every test that wants a value declares one on the
 * simulator first, because there is no stored fallback to fall back to.
 */
class BalanceApiIT extends PostgresSpringBootIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();

    @DynamicPropertySource
    static void mtnPointsAtTheSimulator(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> "test-subscription-key");
        registry.add("nkap.provider.mtn.api-user", () -> "test-api-user");
        registry.add("nkap.provider.mtn.api-key", () -> "test-api-key");
        registry.add("nkap.provider.mtn.currency", () -> "EUR");
        registry.add("nkap.provider.mtn.country", () -> "sandbox");
        registry.add("nkap.provider.mtn.request-timeout", () -> "PT2S");
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

    private String adminKey;

    @BeforeEach
    void setUp() {
        SIMULATOR.reset();
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "BalanceApiIT").token();
    }

    @Test
    @DisplayName("an admin key reads the live balance MTN reports right now")
    void an_admin_key_reads_the_live_balance() throws Exception {
        SIMULATOR.declareScenario("""
                {"account":{"balance":{"availableBalance":"1234.56","currency":"EUR"}}}""");

        ResponseEntity<String> response = getBalance("COLLECT", "EUR", adminKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("provider").asText()).isEqualTo("mtn");
        assertThat(body.get("operation").asText()).isEqualTo("COLLECT");
        assertThat(body.get("amountMinorUnits").asLong()).isEqualTo(123456);
        assertThat(body.get("currency").asText()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("a merchant key on the balance is 403; no key is 401")
    void the_balance_is_admin_gated() throws Exception {
        String merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "BalanceApiIT").token();

        assertThat(getBalance("COLLECT", "EUR", merchantKey).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getBalance("COLLECT", "EUR", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an operator that does not answer is 503, problem+json, not 500 and not a zero balance")
    void an_unanswered_balance_is_503_not_500() throws Exception {
        SIMULATOR.declareScenario("""
                {"account":{"balance":{"outcome":"NO_RESPONSE"}}}""");

        ResponseEntity<String> response = getBalance("COLLECT", "EUR", adminKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json.readTree(response.getBody()).get("type").asText()).endsWith("operator-did-not-answer");
    }

    @Test
    @DisplayName("a malformed operation is 400 with the invalid-request type")
    void a_malformed_operation_is_400() throws Exception {
        ResponseEntity<String> response = getBalance("NOT_AN_OPERATION", "EUR", adminKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(response.getBody()).get("type").asText()).endsWith("invalid-request");
    }

    @Test
    @DisplayName("a missing query parameter is 400, not a framework default page")
    void a_missing_parameter_is_400() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminKey);
        ResponseEntity<String> response = http.exchange(
                "/balance?operation=COLLECT", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(response.getBody()).get("type").asText()).endsWith("invalid-request");
    }

    private ResponseEntity<String> getBalance(String operation, String currency, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return http.exchange("/balance?operation=" + operation + "&currency=" + currency,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
