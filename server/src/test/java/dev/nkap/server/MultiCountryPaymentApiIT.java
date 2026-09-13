package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Issue #82's Test 1: two installations configured, each hitting its own base URL with its
 * own credentials — asserted on the request, not on a mock. Two real, separately-configured
 * {@link EmbeddedSimulator} instances, one per installation ({@code mtn-cm}, XAF;
 * {@code mtn-gh}, GHS), each declared with a <em>different</em> scenario. A payment
 * routed to the wrong installation would see the other simulator's scenario — the happy
 * path, since only {@code mtn-cm}'s simulator is told to refuse — so the observable
 * outcome (rejected vs. settled) is the proof the request reached the installation the
 * country named, not a mock recording what it was called with.
 *
 * <p>Also Test 2 (a currency that disagrees with the named country's installation) and,
 * jointly with {@code PaymentApiIT}, the shape of Test 3 (an unconfigured country) — this
 * file adds the case specific to having more than one real installation to disagree with.
 */
class MultiCountryPaymentApiIT extends PostgresSpringBootIT {

    private static final EmbeddedSimulator CAMEROON = EmbeddedSimulator.start();
    private static final EmbeddedSimulator GHANA = EmbeddedSimulator.start();

    @DynamicPropertySource
    static void twoInstallations(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.installations[0].base-url", CAMEROON::baseUri);
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "cameroon-subscription-key");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "cameroon-api-user");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "cameroon-api-key");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "XAF");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "cm");
        registry.add("nkap.provider.mtn.installations[0].request-timeout", () -> "PT2S");

        registry.add("nkap.provider.mtn.installations[1].base-url", GHANA::baseUri);
        registry.add("nkap.provider.mtn.installations[1].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[1].subscription-key", () -> "ghana-subscription-key");
        registry.add("nkap.provider.mtn.installations[1].api-user", () -> "ghana-api-user");
        registry.add("nkap.provider.mtn.installations[1].api-key", () -> "ghana-api-key");
        registry.add("nkap.provider.mtn.installations[1].currency", () -> "GHS");
        registry.add("nkap.provider.mtn.installations[1].country", () -> "gh");
        registry.add("nkap.provider.mtn.installations[1].request-timeout", () -> "PT2S");

        registry.add("nkap.provider.default", () -> "mtn-cm");
    }

    @AfterAll
    static void stopSimulators() {
        CAMEROON.close();
        GHANA.close();
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApiKeyStore apiKeys;

    @Autowired
    JdbcTemplate jdbc;

    private String apiKey;
    // Every IT test shares one PostgreSQL instance for the life of the JVM. A payment left
    // SUBMITTED or UNKNOWN stays claimable by any other test's real (enabled) reconciler —
    // DisbursementApiIT's, in particular — which has no adapter for mtn-cm or mtn-gh and
    // fails outright on one. Tracked and neutralized in @AfterEach, the same fix applied to
    // ReconcilerIT's fixtures for the same reason.
    private final List<String> pendingReferences = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CAMEROON.reset();
        GHANA.reset();
        // Only Cameroon's simulator is told to refuse outright; Ghana's stays on the
        // default happy path. A payment that reached the wrong installation would see
        // the other one's behaviour, not this one's.
        CAMEROON.declareScenario("""
                {"rules":[{"scenario":{"onSubmit":{"outcome":"BAD_REQUEST"}}}]}""");
        apiKey = apiKeys.provision("merchant-1", false, "MultiCountryPaymentApiIT").token();
    }

    @AfterEach
    void neutralizePendingPayments() {
        for (String reference : pendingReferences) {
            jdbc.update("UPDATE payment SET state = 'FAILED' WHERE reference = ?::uuid", reference);
        }
        pendingReferences.clear();
    }

    private static String body(String country, long amountMinorUnits, String currency) {
        return """
                {"operation":"COLLECT","amount":%d,"currency":"%s","country":"%s",
                 "counterpartyMsisdn":"46733123453","payerMessage":"rent","payeeNote":"march"}"""
                .formatted(amountMinorUnits, currency, country);
    }

    private ResponseEntity<String> post(String requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> response = http.postForEntity("/payments", new HttpEntity<>(requestBody, headers), String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            JsonNode reference = parse(response.getBody()).get("reference");
            if (reference != null) {
                pendingReferences.add(reference.asText());
            }
        }
        return response;
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + s, e);
        }
    }

    @Test
    @DisplayName("a payment routed to Cameroon is refused by Cameroon's own simulator, not Ghana's happy path")
    void cameroon_hits_its_own_simulator_and_is_refused() {
        ResponseEntity<String> response = post(body("cm", 5000, "XAF"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = parse(response.getBody());
        assertThat(payment.get("provider").asText()).isEqualTo("mtn-cm");
        assertThat(payment.get("state").asText())
                .as("Cameroon's simulator was told to refuse; Ghana's was not — this proves which one answered")
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a payment routed to Ghana hits its own simulator's happy path, not Cameroon's refusal")
    void ghana_hits_its_own_simulator_and_succeeds() {
        ResponseEntity<String> response = post(body("gh", 5000, "GHS"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = parse(response.getBody());
        assertThat(payment.get("provider").asText()).isEqualTo("mtn-gh");
        assertThat(payment.get("state").asText())
                .as("Ghana's simulator was never told to refuse; only Cameroon's was")
                .isEqualTo("SUBMITTED");
        assertThat(payment.get("currency").asText()).isEqualTo("GHS");
    }

    @Test
    @DisplayName("a currency that disagrees with the named country's installation is refused before anything is persisted")
    void a_currency_disagreeing_with_the_country_is_refused() {
        String mismatchedBody = body("gh", 5000, "XAF"); // Ghana settles GHS, not XAF

        ResponseEntity<String> rejected = post(mismatchedBody);

        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        JsonNode problem = parse(rejected.getBody());
        assertThat(problem.get("type").asText()).endsWith("unserved-currency");
        assertThat(problem.get("detail").asText()).contains("mtn-gh").contains("GHS");
    }
}
