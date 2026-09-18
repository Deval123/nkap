package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.support.LogCapture;
import dev.nkap.server.support.PostgresSpringBootIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway-level rules the conformance kit cannot hold, because they are properties of
 * this state machine and this idempotency store rather than of any adapter: a duplicate
 * callback settles once, and a callback that overtakes the submit response is not lost.
 * Driven end to end — a real server, a real MTN adapter, a real simulator over HTTP, and a
 * real PostgreSQL behind the stores.
 */
class CallbackApiIT extends PostgresSpringBootIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();

    @DynamicPropertySource
    static void mtnPointsAtTheSimulator(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.installations[0].base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "test-subscription-key");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "test-api-user");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "test-api-key");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "EUR");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "sandbox");
        // Wide enough that a two-second submit delay is not itself a timeout.
        registry.add("nkap.provider.mtn.installations[0].request-timeout", () -> "PT5S");
        registry.add("nkap.provider.default", () -> "mtn-sandbox");
    }

    @AfterAll
    static void stopSimulator() {
        SIMULATOR.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    Ledger ledger;

    @Autowired
    MeterRegistry registry;

    @Autowired
    dev.nkap.server.auth.ApiKeyStore apiKeys;

    private String apiKey;

    @BeforeEach
    void resetSimulator() {
        SIMULATOR.reset();
        apiKey = apiKeys.provision("merchant-1", false, "CallbackApiIT").token();
    }

    // --- helpers -------------------------------------------------------------

    private static String paymentBody(long amountMinorUnits) {
        return """
            {"operation":"COLLECT","amount":%d,"currency":"EUR","country":"sandbox",
             "counterpartyMsisdn":"46733123453","payerMessage":"rent","payeeNote":"march"}"""
                .formatted(amountMinorUnits);
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + s, e);
        }
    }

    private ResponseEntity<String> postPayment(String requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.postForEntity("/payments", new HttpEntity<>(requestBody, headers), String.class);
    }

    /** The callback endpoint takes no credential — that is the point of this helper carrying none. */
    private ResponseEntity<String> postCallback(String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/callbacks/mtn-sandbox", new HttpEntity<>(bodyJson, headers), String.class);
    }

    private static String callback(String reference, String status) {
        return "{\"referenceId\":\"" + reference + "\",\"status\":\"" + status + "\"}";
    }

    private ResponseEntity<String> getPaymentResponse(String reference) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return http.exchange("/payments/" + reference, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private JsonNode getPayment(String reference) {
        return parse(getPaymentResponse(reference).getBody());
    }

    /** Creates a payment, asserts it is SUBMITTED, and returns its reference. */
    private String submittedPayment() {
        ResponseEntity<String> created = postPayment(paymentBody(5000));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = parse(created.getBody());
        assertThat(payment.get("state").asText()).isEqualTo("SUBMITTED");
        return payment.get("reference").asText();
    }

    // --- the marquee two: real async delivery -------------------------------

    @Test
    @DisplayName("a callback arriving before the submit response is not lost: two CALLBACK transitions and one entry")
    void a_callback_before_the_submit_response_settles_the_payment() {
        SIMULATOR.declareScenario("""
            {"callbackUrl":"%s",
             "rules":[{"scenario":{"name":"callback-before-response",
                                   "onSubmit":{"delay":"PT2S","outcome":"ACCEPT"},
                                   "onQuery":[{"status":"SUCCESSFUL"}],
                                   "callbacks":[{"after":"PT0S","status":"SUCCESSFUL"}]}}]}"""
                .formatted(gatewayCallbackUrl()));

        ResponseEntity<String> created = postPayment(paymentBody(5000));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String reference = parse(created.getBody()).get("reference").asText();

        await().atMost(Duration.ofSeconds(10))
                .until(() -> "SUCCEEDED".equals(getPayment(reference).get("state").asText()));

        JsonNode history = getPayment(reference).get("history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("from").asText()).isEqualTo("CREATED");
        assertThat(history.get(0).get("to").asText()).isEqualTo("SUBMITTED");
        assertThat(history.get(0).get("cause").asText()).isEqualTo("CALLBACK");
        assertThat(history.get(1).get("to").asText()).isEqualTo("SUCCEEDED");
        assertThat(history.get(1).get("cause").asText()).isEqualTo("CALLBACK");
        assertThat(ledger.entriesForReference(reference)).hasSize(1);
    }

    @Test
    @DisplayName("a duplicate callback produces one transition and one ledger entry, not two")
    void a_duplicate_callback_settles_once() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        String reference = submittedPayment();

        assertThat(postCallback(callback(reference, "SUCCESSFUL")).getStatusCode().value()).isEqualTo(202);
        assertThat(postCallback(callback(reference, "SUCCESSFUL")).getStatusCode().value()).isEqualTo(202);

        JsonNode payment = getPayment(reference);
        assertThat(payment.get("state").asText()).isEqualTo("SUCCEEDED");
        long callbackTransitions = countCauses(payment.get("history"), "CALLBACK");
        assertThat(callbackTransitions).isEqualTo(1);
        assertThat(ledger.entriesForReference(reference)).hasSize(1);
    }

    // --- the gateway-logic rules ------------------------------------------------

    @Test
    @DisplayName("a settled collection posts exactly the two postings of ADR 0006, in the payment's currency, summing to zero")
    void a_settled_collection_posts_the_two_postings_of_adr_0006() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        String reference = submittedPayment();

        assertThat(postCallback(callback(reference, "SUCCESSFUL")).getStatusCode().value()).isEqualTo(202);

        assertThat(ledger.entriesForReference(reference)).singleElement().satisfies(entry -> {
            assertThat(entry.postings()).hasSize(2);
            assertThat(entry.currency()).isEqualTo(Currency.EUR);
            assertThat(sumSigned(entry)).isZero();
            assertThat(signedAmount(entry, AccountId.providerFloat("mtn-sandbox", Currency.EUR))).isEqualTo(5000L);
            assertThat(signedAmount(entry, AccountId.merchantPayable("merchant-1", Currency.EUR))).isEqualTo(-5000L);
        });
    }

    @Test
    @DisplayName("issue #129: a callback that settles a payment produces both the transition line and the confirmed counter")
    void a_settling_callback_produces_a_line_and_a_counter() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        String reference = submittedPayment();
        double confirmedBefore = counter("nkap.callback.confirmed", "provider", "mtn-sandbox");

        try (LogCapture logs = new LogCapture(SettlementService.class)) {
            assertThat(postCallback(callback(reference, "SUCCESSFUL")).getStatusCode().value()).isEqualTo(202);

            assertThat(logs.events())
                    .as("the exact gap issue #129 found: a callback that settled a payment left nothing grep-able as CALLBACK")
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("CALLBACK").contains(reference).contains("SUCCEEDED"));
        }
        assertThat(counter("nkap.callback.confirmed", "provider", "mtn-sandbox")).isEqualTo(confirmedBefore + 1);
    }

    @Test
    @DisplayName("a callback for a reference this gateway never issued is 202 with nothing written or transitioned")
    void a_callback_for_an_unknown_reference_is_202_and_writes_nothing() {
        String strangerReference = UUID.randomUUID().toString();

        ResponseEntity<String> answer = postCallback(callback(strangerReference, "SUCCESSFUL"));

        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(ledger.entriesForReference(strangerReference)).isEmpty();
        assertThat(getPaymentResponse(strangerReference).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("an unparseable callback body is 400, and nothing is written")
    void an_unparseable_callback_is_400() {
        int entriesBefore = ledger.entries().size();

        ResponseEntity<String> answer = postCallback("this is not a callback");

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(parse(answer.getBody()).get("type").asText()).endsWith("unparseable-callback");
        assertThat(ledger.entries()).hasSize(entriesBefore);
    }

    @Test
    @DisplayName("issue #129: an unparseable callback's body never reaches the log, even a body that would be obvious if it did")
    void an_unparseable_callback_body_never_reaches_the_log() {
        // A valid JSON object, so it clears "not JSON" / "not an object" -- and fails at
        // the next check instead, MtnCollectionsAdapter's own exception embedding this
        // exact string, the way a real attacker-controlled body would. The deterministic
        // regression guard is CallbackControllerTest, with a fresh controller per test; this
        // one runs the real adapter, and shares the class's "log at most once ever" gate
        // with the other unparseable-callback test above, so it can legitimately capture
        // nothing at all if that one already ran first -- either way, the marker must not
        // be among whatever was captured.
        String marker = "MARKER-body-must-never-reach-the-log-" + System.nanoTime();
        String bodyWithMarker = "{\"referenceId\":\"" + marker + "\",\"status\":\"SUCCESSFUL\"}";

        try (LogCapture logs = new LogCapture("dev.nkap.server.web.CallbackController")) {
            ResponseEntity<String> answer = postCallback(bodyWithMarker);

            assertThat(answer.getStatusCode().value()).isEqualTo(400);
            assertThat(logs.events())
                    .as("the parse failure's own message embeds the offending field -- it must never be logged")
                    .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(marker));
        }
    }

    @Test
    @DisplayName("a callback whose confirming query comes back inconclusive leaves the payment untouched")
    void an_inconclusive_confirming_query_changes_nothing() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        String reference = submittedPayment();

        // The operator forgets the reference: its query now answers 404 -> UNKNOWN, which
        // is not a confirmation, so nothing may change.
        SIMULATOR.reset();

        assertThat(postCallback(callback(reference, "SUCCESSFUL")).getStatusCode().value()).isEqualTo(202);

        assertThat(getPayment(reference).get("state").asText()).isEqualTo("SUBMITTED");
        assertThat(ledger.entriesForReference(reference)).isEmpty();
    }

    @Test
    @DisplayName("a late callback cannot reopen an already-terminal payment")
    void a_late_callback_does_not_reopen_a_terminal_payment() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"BAD_REQUEST"}}}]}""");
        ResponseEntity<String> created = postPayment(paymentBody(5000));
        assertThat(parse(created.getBody()).get("state").asText()).isEqualTo("FAILED");
        String reference = parse(created.getBody()).get("reference").asText();

        assertThat(postCallback(callback(reference, "SUCCESSFUL")).getStatusCode().value()).isEqualTo(202);

        assertThat(getPayment(reference).get("state").asText()).isEqualTo("FAILED");
        assertThat(countCauses(getPayment(reference).get("history"), "CALLBACK")).isZero();
        assertThat(ledger.entriesForReference(reference)).isEmpty();
    }

    // --- small assertion helpers ------------------------------------------------

    private String gatewayCallbackUrl() {
        return "http://localhost:" + port + "/callbacks/mtn-sandbox";
    }

    /** 0 rather than a lookup failure: a counter this test's own traffic has not touched yet is absent, not zero. */
    private double counter(String name, String tagKey, String tagValue) {
        var found = registry.find(name).tag(tagKey, tagValue).counter();
        return found == null ? 0.0 : found.count();
    }

    private static long countCauses(JsonNode history, String cause) {
        long count = 0;
        for (JsonNode transition : history) {
            if (cause.equals(transition.get("cause").asText())) {
                count++;
            }
        }
        return count;
    }

    private static long signedAmount(LedgerEntry entry, AccountId account) {
        return entry.postings().stream()
                .filter(p -> p.account().equals(account))
                .mapToLong(p -> p.amount().amount())
                .sum();
    }

    private static long sumSigned(LedgerEntry entry) {
        return entry.postings().stream().mapToLong(p -> p.amount().amount()).sum();
    }
}
