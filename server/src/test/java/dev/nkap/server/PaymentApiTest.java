package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The failures this slice exists to survive, driven end to end: a real server, a real MTN
 * adapter, a real simulator over HTTP. Not coverage — these six.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiTest {

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

    @BeforeEach
    void resetSimulator() {
        SIMULATOR.reset();
    }

    private static String body(long amountMinorUnits) {
        return """
            {"merchantId":"merchant-1","operation":"COLLECT","amount":%d,"currency":"EUR",
             "counterpartyMsisdn":"46733123453","payerMessage":"rent","payeeNote":"march"}"""
                .formatted(amountMinorUnits);
    }

    private ResponseEntity<String> post(String key, String requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.postForEntity("/payments", new HttpEntity<>(requestBody, headers), String.class);
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + s, e);
        }
    }

    @Test
    @DisplayName("two POSTs with the same key and body: one payment, the second answered by verbatim replay")
    void a_replayed_key_returns_the_first_answer_and_creates_nothing_new() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = post(key, body(5000));
        ResponseEntity<String> second = post(key, body(5000));

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(parse(second.getBody())).isEqualTo(parse(first.getBody()));
        // Same reference means no second payment was created.
        assertThat(parse(second.getBody()).get("reference").asText())
                .isEqualTo(parse(first.getBody()).get("reference").asText());
        assertThat(parse(first.getBody()).get("state").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("the same key with a different body is 409 and submits nothing")
    void a_reused_key_with_a_different_body_conflicts() {
        String key = UUID.randomUUID().toString();
        post(key, body(5000));

        ResponseEntity<String> conflict = post(key, body(9000));

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(parse(conflict.getBody()).get("type").asText()).endsWith("idempotency-key-reuse");
    }

    @Test
    @DisplayName("the operator never answering is 202 and UNKNOWN, never 500 and never FAILED, and it is persisted")
    void operator_silence_is_202_unknown_and_persisted() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> created = post(key, body(5000));

        assertThat(created.getStatusCode().value()).isEqualTo(202);
        JsonNode payment = parse(created.getBody());
        assertThat(payment.get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(payment.get("detail").asText()).contains("poll").contains("not yet known");
        String reference = payment.get("reference").asText();

        ResponseEntity<String> read = http.getForEntity("/payments/" + reference, String.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        JsonNode stored = parse(read.getBody());
        assertThat(stored.get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(stored.get("history")).hasSize(1);
        assertThat(stored.get("history").get(0).get("from").asText()).isEqualTo("CREATED");
        assertThat(stored.get("history").get(0).get("to").asText()).isEqualTo("UNKNOWN");
        assertThat(stored.get("history").get(0).get("cause").asText()).isEqualTo("SUBMIT_RESPONSE");
    }

    @Test
    @DisplayName("an outright refusal is 201 describing a FAILED payment, its transition attributed to the submit response")
    void an_operator_refusal_is_201_failed() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"BAD_REQUEST"}}}]}""");
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> created = post(key, body(5000));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = parse(created.getBody());
        assertThat(payment.get("state").asText()).isEqualTo("FAILED");
        assertThat(payment.get("history")).hasSize(1);
        assertThat(payment.get("history").get(0).get("to").asText()).isEqualTo("FAILED");
        assertThat(payment.get("history").get(0).get("cause").asText()).isEqualTo("SUBMIT_RESPONSE");
        // The operator's code would be recorded here; the simulator does not send one yet (issue #26).
    }

    @Test
    @DisplayName("the happy path is 201 SUBMITTED, and GET returns the stored state and a coherent history")
    void the_happy_path_is_readable_afterwards() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> created = post(key, body(5000));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        String reference = parse(created.getBody()).get("reference").asText();

        ResponseEntity<String> read = http.getForEntity("/payments/" + reference, String.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        JsonNode payment = parse(read.getBody());
        assertThat(payment.get("state").asText()).isEqualTo("SUBMITTED");
        assertThat(payment.get("provider").asText()).isEqualTo("mtn");
        assertThat(payment.get("amountMinorUnits").asLong()).isEqualTo(5000);
        assertThat(payment.get("currency").asText()).isEqualTo("EUR");
        assertThat(payment.get("history")).hasSize(1);
        assertThat(payment.get("history").get(0).get("from").asText()).isEqualTo("CREATED");
        assertThat(payment.get("history").get(0).get("to").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("a GET on an unknown reference is 404, and on a non-reference is 400")
    void reads_of_missing_and_malformed_references() {
        ResponseEntity<String> missing = http.getForEntity("/payments/" + UUID.randomUUID(), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(parse(missing.getBody()).get("type").asText()).endsWith("payment-not-found");

        ResponseEntity<String> malformed = http.getForEntity("/payments/not-a-reference", String.class);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(parse(malformed.getBody()).get("type").asText()).endsWith("malformed-reference");
    }

    @Test
    @DisplayName("an amount that is not a whole number of minor units is 400, and leaves no idempotency claim behind")
    void a_non_integer_amount_is_rejected_and_persists_nothing() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> rejected = post(key, body(5000).replace("\"amount\":5000", "\"amount\":50.5"));

        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(parse(rejected.getBody()).get("type").asText()).endsWith("malformed-request");

        // The same key now works: the rejected request claimed nothing.
        ResponseEntity<String> retry = post(key, body(5000));
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(parse(retry.getBody()).get("state").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("a currency the resolved adapter does not settle is a 201 describing a FAILED payment, not a 500")
    void a_currency_the_adapter_cannot_settle_is_a_failed_payment() {
        String key = UUID.randomUUID().toString();
        String xafBody = body(5000).replace("\"currency\":\"EUR\"", "\"currency\":\"XAF\"");

        ResponseEntity<String> created = post(key, xafBody);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = parse(created.getBody());
        assertThat(payment.get("state").asText()).isEqualTo("FAILED");
        assertThat(payment.get("history").get(0).get("operatorCode").asText()).isEqualTo("ADAPTER_REFUSED_INTENT");
    }

    @Test
    @DisplayName("POST without an Idempotency-Key is 400")
    void the_idempotency_key_is_required() {
        ResponseEntity<String> noKey = post(null, body(5000));

        assertThat(noKey.getStatusCode().value()).isEqualTo(400);
        assertThat(parse(noKey.getBody()).get("type").asText()).endsWith("missing-idempotency-key");
    }
}
