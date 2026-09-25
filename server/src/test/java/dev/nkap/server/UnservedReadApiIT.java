package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.support.PostgresSpringBootIT;
import dev.nkap.server.support.StubReceiver;
import dev.nkap.server.support.StubReceiver.RecordedRequest;
import dev.nkap.server.support.StubReceiver.StubResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 * An MTN installation configured for Collections only, settling in EUR, as the default
 * provider: it declares {@code BALANCE} and {@code HOLDER_VALIDATION}, but not
 * {@code DISBURSE}, and not any currency but its own. A read naming either is refused with a
 * {@code 400} and a problem type, before the adapter is called — where it used to reach the
 * adapter, which refused by throwing, and the caller got an untyped {@code 500}.
 *
 * <p>The operator is a stub that records every request, token calls included, not the
 * simulator: the simulator counts submissions, and a balance or holder read is neither, so it
 * cannot say whether one arrived. The stub can. The {@code 200} cases below are what make an
 * empty record mean something — the same stub, the same context, does receive the reads this
 * installation can serve.
 */
class UnservedReadApiIT extends PostgresSpringBootIT {

    private static final StubReceiver MTN = startStub();
    private static final String MSISDN = "46733123453";

    private static StubReceiver startStub() {
        try {
            return new StubReceiver();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void mtnCollectionsOnly(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.installations[0].base-url", () -> MTN.baseUrl().resolve("/").toString());
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "test-subscription-key");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "test-api-user");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "test-api-key");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "EUR");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].request-timeout", () -> "PT2S");
        registry.add("nkap.provider.default", () -> "mtn-sandbox");
    }

    @AfterAll
    static void stopStub() {
        MTN.close();
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
        MTN.requests.clear();
        MTN.respondWith(UnservedReadApiIT::collections);
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "UnservedReadApiIT").token();
    }

    /** MTN Collections' token, balance and account-holder answers, in the simulator's shapes. */
    private static StubResponse collections(RecordedRequest request) {
        if (request.path().equals("/collection/token/")) {
            return new StubResponse(200, """
                    {"access_token":"test-token","token_type":"access_token","expires_in":3600}""");
        }
        if (request.path().equals("/collection/v1_0/account/balance")) {
            return new StubResponse(200, """
                    {"availableBalance":"1234.56","currency":"EUR"}""");
        }
        if (request.path().equals("/collection/v1_0/accountholder/msisdn/" + MSISDN + "/active")) {
            return new StubResponse(200, """
                    {"result":true}""");
        }
        return new StubResponse(404, "{}");
    }

    @Test
    @DisplayName("GET /balance for an operation the default does not declare is 400 operation-not-served, and no request reaches the operator")
    void balance_for_an_undeclared_operation_is_refused_without_asking_the_operator() throws Exception {
        ResponseEntity<String> response = get("/balance?operation=DISBURSE&currency=EUR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.get("type").asText()).endsWith("/operation-not-served");
        assertThat(problem.get("detail").asText()).contains("DISBURSE").contains("mtn-sandbox").contains("[COLLECT]");
        assertThat(MTN.requests).as("the operator was contacted").isEmpty();
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn} for an operation the default does not declare is 400 operation-not-served, and no request reaches the operator")
    void holder_for_an_undeclared_operation_is_refused_without_asking_the_operator() throws Exception {
        ResponseEntity<String> response = get("/account-holders/" + MSISDN + "?operation=DISBURSE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.get("type").asText()).endsWith("/operation-not-served");
        assertThat(problem.get("detail").asText()).contains("DISBURSE").contains("mtn-sandbox");
        assertThat(MTN.requests).as("the operator was contacted").isEmpty();
    }

    @Test
    @DisplayName("GET /balance in a currency this installation does not settle is 400 unserved-currency, the POST /payments answer, and no request reaches the operator")
    void balance_in_an_unserved_currency_is_refused_without_asking_the_operator() throws Exception {
        ResponseEntity<String> response = get("/balance?operation=COLLECT&currency=XAF");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.get("type").asText()).endsWith("/unserved-currency");
        assertThat(problem.get("detail").asText()).contains("mtn-sandbox settles in EUR").contains("XAF");
        assertThat(MTN.requests).as("the operator was contacted").isEmpty();
    }

    @Test
    @DisplayName("GET /balance for what the default does declare still answers 200 from the operator -- the check does not refuse too much")
    void a_declared_balance_read_still_reaches_the_operator() throws Exception {
        ResponseEntity<String> response = get("/balance?operation=COLLECT&currency=EUR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).get("amountMinorUnits").asLong()).isEqualTo(123456);
        assertThat(MTN.requests).extracting(RecordedRequest::path).contains("/collection/v1_0/account/balance");
    }

    @Test
    @DisplayName("GET /account-holders/{msisdn} for what the default does declare still answers 200 from the operator -- the check does not refuse too much")
    void a_declared_holder_read_still_reaches_the_operator() throws Exception {
        ResponseEntity<String> response = get("/account-holders/" + MSISDN + "?operation=COLLECT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).get("active").asBoolean()).isTrue();
        assertThat(MTN.requests).extracting(RecordedRequest::path)
                .contains("/collection/v1_0/accountholder/msisdn/" + MSISDN + "/active");
    }

    private ResponseEntity<String> get(String pathAndQuery) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminKey);
        return http.exchange(pathAndQuery, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
