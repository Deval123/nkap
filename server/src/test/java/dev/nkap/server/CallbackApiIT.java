package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.server.support.PostgresSpringBootIT;
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
        registry.add("nkap.provider.mtn.base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> "test-subscription-key");
        registry.add("nkap.provider.mtn.api-user", () -> "test-api-user");
        registry.add("nkap.provider.mtn.api-key", () -> "test-api-key");
        registry.add("nkap.provider.mtn.currency", () -> "EUR");
        registry.add("nkap.provider.mtn.country", () -> "sandbox");
        // Wide enough that a two-second submit delay is not itself a timeout.
        registry.add("nkap.provider.mtn.request-timeout", () -> "PT5S");
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

    @BeforeEach
    void resetSimulator() {
        SIMULATOR.reset();
    }

    // --- helpers -------------------------------------------------------------

    private static String paymentBody(long amountMinorUnits) {
        return """
            {"merchantId":"merchant-1","operation":"COLLECT","amount":%d,"currency":"EUR",
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
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.postForEntity("/payments", new HttpEntity<>(requestBody, headers), String.class);
    }

    private ResponseEntity<String> postCallback(String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/callbacks/mtn", new HttpEntity<>(bodyJson, headers), String.class);
    }

    private static String callback(String reference, String status) {
        return "{\"referenceId\":\"" + reference + "\",\"status\":\"" + status + "\"}";
    }

    private JsonNode getPayment(String reference) {
        return parse(http.getForEntity("/payments/" + reference, String.class).getBody());
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
            assertThat(signedAmount(entry, AccountId.providerFloat("mtn", Currency.EUR))).isEqualTo(5000L);
            assertThat(signedAmount(entry, AccountId.merchantPayable("merchant-1", Currency.EUR))).isEqualTo(-5000L);
        });
    }

    @Test
    @DisplayName("a callback for a reference this gateway never issued is 202 with nothing written or transitioned")
    void a_callback_for_an_unknown_reference_is_202_and_writes_nothing() {
        String strangerReference = UUID.randomUUID().toString();

        ResponseEntity<String> answer = postCallback(callback(strangerReference, "SUCCESSFUL"));

        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(ledger.entriesForReference(strangerReference)).isEmpty();
        assertThat(http.getForEntity("/payments/" + strangerReference, String.class).getStatusCode().value())
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
        return "http://localhost:" + port + "/callbacks/mtn";
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
