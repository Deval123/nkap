package dev.nkap.simulator.mpesa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.testsupport.LoopbackOnly;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * {@code GET /_nkap/received}: what the M-Pesa face received, recorded and never checked. The
 * point of it is that a test can see a rotated credential arrive, so these tests prove the record
 * and, as firmly, that recording changed nothing about what the face accepts.
 */
// The address its tests dial, not the wildcard Tomcat binds by default (issue #221): see
// LoopbackOnly for what another process could otherwise do with 127.0.0.1 on this port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=" + LoopbackOnly.ADDRESS)
@ExtendWith(OutputCaptureExtension.class)
class MpesaReceivedTest {

    private static final String KEY_1 = "canary-consumer-key-one-4d2a";
    private static final String SECRET_1 = "canary-consumer-secret-one-8e1b";
    private static final String KEY_2 = "canary-consumer-key-two-6c3f";
    private static final String SECRET_2 = "canary-consumer:secret-two-0a9d";
    private static final String PASSKEY = "canary-passkey-3b7e";
    private static final List<String> CANARIES = List.of(KEY_1, SECRET_1, KEY_2, SECRET_2, PASSKEY);

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private RestClient client;

    private String base() {
        return "http://localhost:" + port;
    }

    @BeforeEach
    @AfterEach
    void reset() {
        client = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
        client.delete().uri(base() + "/_nkap/scenarios").retrieve().toBodilessEntity();
        client.delete().uri(base() + "/_nkap/state").retrieve().toBodilessEntity();
    }

    // --- the token face --------------------------------------------------------------------

    @Test
    @DisplayName("the application under test binds 127.0.0.1 alone, so no other listener can take the address its tests dial")
    void it_binds_loopback_only() throws Exception {
        LoopbackOnly.assertBoundToLoopbackOnly(port);
    }

    @Test
    @DisplayName("a token request's Basic credentials are recorded decoded, and the next pair replaces them")
    void a_token_request_records_its_pair() {
        assertThat(token(KEY_1, SECRET_1).getStatusCode().value()).isEqualTo(200);
        JsonNode first = received();
        assertThat(first.get("tokenRequests").asInt()).isEqualTo(1);
        assertThat(first.at("/lastTokenRequest/consumerKey").asText()).isEqualTo(KEY_1);
        assertThat(first.at("/lastTokenRequest/consumerSecret").asText()).isEqualTo(SECRET_1);

        token(KEY_2, SECRET_2);

        JsonNode second = received();
        assertThat(second.get("tokenRequests").asInt()).isEqualTo(2);
        assertThat(second.at("/lastTokenRequest/consumerKey").asText()).isEqualTo(KEY_2);
        assertThat(second.at("/lastTokenRequest/consumerSecret").asText()).as("split at the first colon only, RFC 7617")
                .isEqualTo(SECRET_2);
    }

    @Test
    @DisplayName("a token request with no Basic header is still answered, and recorded with no credentials")
    void a_token_request_without_credentials_is_recorded_empty() {
        ResponseEntity<String> answer = client.get().uri(base() + "/oauth/v1/generate?grant_type=client_credentials")
                .retrieve().toEntity(String.class);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(received().at("/lastTokenRequest/consumerKey").isNull()).isTrue();
        assertThat(received().get("tokenRequests").asInt()).isEqualTo(1);
    }

    // --- the submission face ---------------------------------------------------------------

    @Test
    @DisplayName("a submission's BusinessShortCode, Timestamp and Password are recorded as they arrived, and base64(shortcode + passkey + timestamp) recomputed from the record matches")
    void a_submission_records_what_its_password_is_computed_from() {
        String timestamp = "20260925183012";
        String password = password("174379", PASSKEY, timestamp);

        assertThat(submit(174379, timestamp, password).getStatusCode().value()).isEqualTo(200);

        JsonNode submission = received().get("lastSubmission");
        assertThat(submission.get("businessShortCode").asText()).as("sent as a JSON number, recorded as its text")
                .isEqualTo("174379");
        assertThat(submission.get("timestamp").asText()).isEqualTo(timestamp);
        assertThat(submission.get("password").asText()).isEqualTo(password);
        // The computation a rotation test makes: from the record alone, and the passkey it expects.
        assertThat(password(submission.get("businessShortCode").asText(), PASSKEY, submission.get("timestamp").asText()))
                .isEqualTo(submission.get("password").asText());
        assertThat(received().get("submissionRequests").asInt()).isEqualTo(1);
    }

    // --- recording is not checking ---------------------------------------------------------

    @Test
    @DisplayName("a wrong Consumer Key is still issued a token, and a Password from a wrong passkey is still accepted: recording checks nothing")
    void recording_checks_nothing() {
        assertThat(token("not-the-key", "not-the-secret").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> answer = submit(174379, "20260925183012",
                password("174379", "not-the-passkey", "20260925183012"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(read(answer).get("ResponseCode").asText()).isEqualTo("0");
    }

    // --- resets ----------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /_nkap/state clears the record, with the rest of the state; DELETE /_nkap/scenarios leaves it")
    void only_forgetting_state_clears_the_record() {
        token(KEY_1, SECRET_1);
        submit(174379, "20260925183012", password("174379", PASSKEY, "20260925183012"));

        client.delete().uri(base() + "/_nkap/scenarios").retrieve().toBodilessEntity();
        JsonNode afterScenarioReset = received();
        assertThat(afterScenarioReset.get("tokenRequests").asInt()).as("a declaration reset is not a state reset")
                .isEqualTo(1);
        assertThat(afterScenarioReset.at("/lastTokenRequest/consumerKey").asText()).isEqualTo(KEY_1);

        client.delete().uri(base() + "/_nkap/state").retrieve().toBodilessEntity();
        JsonNode afterStateReset = received();
        assertThat(afterStateReset.get("tokenRequests").asInt()).isZero();
        assertThat(afterStateReset.get("submissionRequests").asInt()).isZero();
        assertThat(afterStateReset.get("lastTokenRequest").isNull()).isTrue();
        assertThat(afterStateReset.get("lastSubmission").isNull()).isTrue();
    }

    // --- what is never printed -------------------------------------------------------------

    @Test
    @DisplayName("nothing the face received appears in its output, and both records mask their credentials in toString()")
    void nothing_received_is_printed(CapturedOutput output) {
        token(KEY_1, SECRET_1);
        token(KEY_2, SECRET_2);
        submit(174379, "20260925183012", password("174379", PASSKEY, "20260925183012"));
        String recorded = new MpesaReceived.TokenRequest(KEY_1, SECRET_1) + " "
                + new MpesaReceived.Submission("174379", "20260925183012", password("174379", PASSKEY, "20260925183012"));

        for (String canary : CANARIES) {
            assertThat(output.getAll()).as("a canary in the output").doesNotContain(canary);
            assertThat(recorded).as("a canary in toString()").doesNotContain(canary);
        }
        assertThat(recorded).doesNotContain(password("174379", PASSKEY, "20260925183012"));
    }

    // --- helpers ---------------------------------------------------------------------------

    private ResponseEntity<String> token(String key, String secret) {
        String basic = Base64.getEncoder().encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return client.get().uri(base() + "/oauth/v1/generate?grant_type=client_credentials")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .retrieve().toEntity(String.class);
    }

    /** The documented example's shape: BusinessShortCode a number, Amount and PartyB strings. */
    private ResponseEntity<String> submit(long shortCode, String timestamp, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", shortCode);
        body.put("Password", password);
        body.put("Timestamp", timestamp);
        body.put("TransactionType", "CustomerPayBillOnline");
        body.put("Amount", "1");
        body.put("PartyA", "254708374149");
        body.put("PartyB", String.valueOf(shortCode));
        body.put("PhoneNumber", "254708374149");
        body.put("CallBackURL", base() + "/nowhere");
        body.put("AccountReference", "ref-" + System.nanoTime());
        body.put("TransactionDesc", "Payment");
        return client.post().uri(base() + "/mpesa/stkpush/v1/processrequest")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toEntity(String.class);
    }

    private static String password(String shortCode, String passkey, String timestamp) {
        return Base64.getEncoder().encodeToString((shortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode received() {
        return read(client.get().uri(base() + "/_nkap/received").retrieve().toEntity(String.class));
    }

    private JsonNode read(ResponseEntity<String> answer) {
        try {
            return json.readTree(answer.getBody());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + answer.getBody(), e);
        }
    }
}
