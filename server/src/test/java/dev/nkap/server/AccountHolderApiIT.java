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
 * {@code GET /account-holders/{msisdn}} end to end: a real server, a real MTN adapter, a
 * real simulator over HTTP. A merchant key is enough — unlike {@code GET /balance} this is
 * not operator-wide data — and an unanswered check is 503, never a quiet "inactive".
 */
class AccountHolderApiIT extends PostgresSpringBootIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();
    private static final String MSISDN = "46733123453";

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

    private String merchantKey;

    @BeforeEach
    void setUp() {
        SIMULATOR.reset();
        merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "AccountHolderApiIT").token();
    }

    @Test
    @DisplayName("a merchant key reads the live holder status MTN reports right now")
    void a_merchant_key_reads_the_live_holder_status() throws Exception {
        SIMULATOR.declareScenario("""
                {"account":{"holder":{"active":true}}}""");

        ResponseEntity<String> response = getHolder(MSISDN, "COLLECT", merchantKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("provider").asText()).isEqualTo("mtn");
        assertThat(body.get("operation").asText()).isEqualTo("COLLECT");
        assertThat(body.get("msisdn").asText()).isEqualTo(MSISDN);
        assertThat(body.get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("an inactive account is a real answer, not the unanswered case")
    void an_inactive_account_is_a_real_answer() throws Exception {
        SIMULATOR.declareScenario("""
                {"account":{"holder":{"active":false}}}""");

        ResponseEntity<String> response = getHolder(MSISDN, "COLLECT", merchantKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).get("active").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("no key is 401 — a merchant key is enough once presented")
    void the_check_still_requires_some_key() {
        assertThat(getHolder(MSISDN, "COLLECT", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an operator that does not answer is 503, problem+json, never a quiet inactive")
    void an_unanswered_check_is_503_never_inactive() throws Exception {
        SIMULATOR.declareScenario("""
                {"account":{"holder":{"outcome":"NO_RESPONSE"}}}""");

        ResponseEntity<String> response = getHolder(MSISDN, "COLLECT", merchantKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json.readTree(response.getBody()).get("type").asText()).endsWith("operator-did-not-answer");
    }

    private ResponseEntity<String> getHolder(String msisdn, String operation, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return http.exchange("/account-holders/" + msisdn + "?operation=" + operation,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
