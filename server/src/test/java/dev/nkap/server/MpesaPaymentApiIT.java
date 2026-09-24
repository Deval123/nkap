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
 * Issue #215: a deployment configured with an M-Pesa installation beside an MTN one routes
 * {@code POST /payments}'s {@code country} to it, and the request reaches the operator.
 *
 * <p>The operator here is a stub answering Daraja's two calls, not {@code simulator-mpesa}:
 * the adapter is already certified against that simulator by the conformance kit, and what
 * this test defends is the wiring — that the country routes to {@code mpesa-ke}, and that the
 * submission carries the per-payment callback address the adapter cannot work without. Both
 * are asserted on the request the stub actually received.
 */
class MpesaPaymentApiIT extends PostgresSpringBootIT {

    private static final StubReceiver DARAJA = startStub();

    private static StubReceiver startStub() {
        try {
            return new StubReceiver();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void mtnAndMpesa(DynamicPropertyRegistry registry) {
        // MTN is configured but never called here: the point is that a country picks between two.
        registry.add("nkap.provider.mtn.installations[0].base-url", () -> "http://127.0.0.1:1");
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "test-subscription-key");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "test-api-user");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "test-api-key");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "XAF");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "cm");
        registry.add("nkap.provider.default", () -> "mtn-cm");

        registry.add("nkap.provider.mpesa.installations[0].base-url", () -> DARAJA.baseUrl().resolve("/").toString());
        registry.add("nkap.provider.mpesa.installations[0].business-short-code", () -> "174379");
        registry.add("nkap.provider.mpesa.installations[0].passkey", () -> "test-passkey");
        registry.add("nkap.provider.mpesa.installations[0].consumer-key", () -> "test-consumer-key");
        registry.add("nkap.provider.mpesa.installations[0].consumer-secret", () -> "test-consumer-secret");
        registry.add("nkap.provider.mpesa.installations[0].currency", () -> "KES");
        registry.add("nkap.provider.mpesa.installations[0].country", () -> "ke");
        registry.add("nkap.provider.mpesa.installations[0].request-timeout", () -> "PT2S");

        registry.add("nkap.public-base-url", () -> "https://gateway.example.com");
    }

    @AfterAll
    static void stopStub() {
        DARAJA.close();
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
    // A SUBMITTED payment stays claimable by another test class's enabled reconciler, which has
    // no adapter for mpesa-ke; neutralized after each test, as MultiCountryPaymentApiIT does.
    private final List<String> pendingReferences = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DARAJA.requests.clear();
        DARAJA.respondWith(MpesaPaymentApiIT::daraja);
        apiKey = apiKeys.provision("merchant-1", false, "MpesaPaymentApiIT").token();
    }

    @AfterEach
    void neutralizePendingPayments() {
        for (String reference : pendingReferences) {
            jdbc.update("UPDATE payment SET state = 'FAILED' WHERE reference = ?::uuid", reference);
        }
        pendingReferences.clear();
    }

    /** Daraja's token call and STK Push acknowledgement, in the shapes the page observed. */
    private static StubResponse daraja(RecordedRequest request) {
        if (request.path().equals("/oauth/v1/generate")) {
            return new StubResponse(200, """
                    {"access_token":"test-token","expires_in":"3599"}""");
        }
        if (request.path().equals("/mpesa/stkpush/v1/processrequest")) {
            return new StubResponse(200, """
                    {"MerchantRequestID":"5dbd-4f93-a478-41cdaf2b9acd86873",
                     "CheckoutRequestID":"ws_CO_240920261930000708374149",
                     "ResponseCode":"0","ResponseDescription":"Success. Request accepted for processing",
                     "CustomerMessage":"Success. Request accepted for processing"}""");
        }
        return new StubResponse(404, "{}");
    }

    private ResponseEntity<String> post(String country, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = """
                {"operation":"COLLECT","amount":100,"currency":"%s","country":"%s",
                 "counterpartyMsisdn":"254708374149","payerMessage":"rent"}""".formatted(currency, country);
        ResponseEntity<String> response = http.postForEntity("/payments", new HttpEntity<>(body, headers), String.class);
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
            throw new AssertionError("not JSON: " + s, e);
        }
    }

    @Test
    @DisplayName("a payment for country ke routes to mpesa-ke and reaches the operator with its own callback address")
    void a_kenyan_payment_reaches_the_mpesa_installation() {
        ResponseEntity<String> response = post("ke", "KES");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = parse(response.getBody());
        assertThat(payment.get("provider").asText()).isEqualTo("mpesa-ke");
        assertThat(payment.get("state").asText()).isEqualTo("SUBMITTED");

        RecordedRequest submission = DARAJA.requests.stream()
                .filter(r -> r.path().equals("/mpesa/stkpush/v1/processrequest"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the STK Push never reached the operator: " + DARAJA.requests));
        JsonNode sent = parse(submission.body());
        assertThat(sent.get("CallBackURL").asText())
                .as("the per-payment address the adapter resolves by")
                .isEqualTo("https://gateway.example.com/callbacks/mpesa-ke/" + payment.get("reference").asText());
        assertThat(sent.get("Amount").asText()).as("100 minor units of KES is one shilling").isEqualTo("1");
    }

    @Test
    @DisplayName("with M-Pesa configured, a payment for country cm still routes to MTN, and never reaches M-Pesa")
    void a_cameroonian_payment_still_routes_to_mtn() {
        ResponseEntity<String> response = post("cm", "XAF");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(parse(response.getBody()).get("provider").asText()).isEqualTo("mtn-cm");
        assertThat(DARAJA.requests).as("nothing addressed to Cameroon reaches Safaricom").isEmpty();
    }

    @Test
    @DisplayName("a currency the Kenyan installation does not settle is refused before anything reaches the operator")
    void a_currency_mpesa_does_not_settle_is_refused() {
        ResponseEntity<String> rejected = post("ke", "XAF");

        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(parse(rejected.getBody()).get("detail").asText()).contains("mpesa-ke").contains("KES");
        assertThat(DARAJA.requests).isEmpty();
    }
}
