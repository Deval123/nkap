package dev.nkap.simulator.mpesa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * M-Pesa's face, end to end through the core: every submission here goes through
 * {@code Submissions}, every query through the engine, every callback through the core's
 * dispatcher, and every scenario through the core's control plane. It runs on a real port
 * because a callback is a real outbound request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class StkPushTest {

    private static final String PHONE = "254708374149";

    @LocalServerPort
    int port;

    @Autowired
    Hook hook;

    @Autowired
    ObjectMapper json;

    @Autowired
    MockMvc mvc;

    private RestClient client;

    private String base() {
        return "http://localhost:" + port;
    }

    private String hookUrl() {
        return base() + "/test-hook/mpesa";
    }

    @BeforeEach
    @AfterEach
    void reset() {
        client = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
        client.delete().uri(base() + "/_nkap/scenarios").retrieve().toBodilessEntity();
        client.delete().uri(base() + "/_nkap/state").retrieve().toBodilessEntity();
        hook.clear();
    }

    // --- authentication --------------------------------------------------------------------

    @Test
    @DisplayName("the token endpoint issues a 28-character bearer token whose expires_in is the declared lifetime")
    void the_token_endpoint_issues_a_28_character_token() {
        declare("""
            {"token":{"ttl":"PT59M59S"}}""");

        JsonNode token = read(client.get().uri(base() + "/oauth/v1/generate?grant_type=client_credentials")
                .header(HttpHeaders.AUTHORIZATION, "Basic a2V5OnNlY3JldA==")
                .retrieve().toEntity(String.class));

        assertThat(token.get("access_token").asText()).hasSize(28);
        assertThat(token.get("expires_in").asLong()).isEqualTo(3599);
    }

    @Test
    @DisplayName("with token enforcement declared, a query without a live token is refused and one with an issued token is answered")
    void enforcement_refuses_a_query_without_a_live_token() {
        declare("""
            {"token":{"enforce":true}}""");
        String token = read(client.get().uri(base() + "/oauth/v1/generate?grant_type=client_credentials")
                .retrieve().toEntity(String.class)).get("access_token").asText();
        String checkoutRequestId = read(submit("order-1", PHONE, null, "Bearer " + token))
                .get("CheckoutRequestID").asText();

        assertThat(query(checkoutRequestId, null).getStatusCode().value()).isEqualTo(401);
        assertThat(query(checkoutRequestId, "Bearer not-a-token-of-ours").getStatusCode().value()).isEqualTo(401);
        assertThat(query(checkoutRequestId, "Bearer " + token).getStatusCode().value()).isEqualTo(200);
    }

    // --- submission and identity ------------------------------------------------------------

    @Test
    @DisplayName("a submission answers with an operator-minted CheckoutRequestID in Nairobi local time, and echoes no AccountReference")
    void a_submission_answers_with_a_minted_identity() {
        String before = ZonedDateTime.now(MpesaPaymentIdentity.NAIROBI).format(DateTimeFormatter.ofPattern("ddMMyyyyHH"));

        ResponseEntity<String> answer = submit("order-no-echo", PHONE, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        JsonNode body = read(answer);
        assertThat(body.get("CheckoutRequestID").asText()).startsWith("ws_CO_" + before).endsWith("708374149");
        assertThat(body.get("MerchantRequestID").asText()).isNotBlank();
        assertThat(body.get("ResponseCode").asText()).isEqualTo("0");
        assertThat(answer.getBody()).doesNotContain("order-no-echo");
    }

    @Test
    @DisplayName("two submissions with the same AccountReference are two independent payments, each with its own identity")
    void the_same_account_reference_twice_is_two_payments() {
        declare("""
            {"rules":[{"scenario":{"onQuery":[{"status":"STILL_PROCESSING"},{"status":"NO_RESPONSE_FROM_USER"}]}}]}""");

        String first = read(submit("order-twice", PHONE, null, null)).get("CheckoutRequestID").asText();
        String second = read(submit("order-twice", PHONE, null, null)).get("CheckoutRequestID").asText();

        assertThat(first).isNotEqualTo(second);
        // Each payment keeps its own place in its own timeline: querying one moves only that one.
        assertThat(resultCode(query(first, null))).isEqualTo(4999);
        assertThat(resultCode(query(first, null))).isEqualTo(1037);
        assertThat(resultCode(query(second, null))).isEqualTo(4999);
    }

    @Test
    @DisplayName("a rule written against AccountReference selects the scenario, since no identity existed when the rule was written")
    void a_rule_matches_the_account_reference() {
        declare("""
            {"rules":[{"match":{"referenceId":"order-times-out"},
                       "scenario":{"name":"times-out","onQuery":[{"status":"NO_RESPONSE_FROM_USER"}]}}]}""");

        String matched = read(submit("order-times-out", PHONE, null, null)).get("CheckoutRequestID").asText();
        String other = read(submit("order-succeeds", PHONE, null, null)).get("CheckoutRequestID").asText();

        assertThat(resultCode(query(matched, null))).isEqualTo(1037);
        assertThat(resultCode(query(other, null))).isEqualTo(0);
        assertThat(read(client.get().uri(base() + "/_nkap/state/" + matched).retrieve().toEntity(String.class))
                .get("scenario").asText()).isEqualTo("times-out");
    }

    @Test
    @DisplayName("a submission declared NO_RESPONSE is never answered")
    void a_no_response_submission_is_never_answered() throws Exception {
        declare("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"}}}]}""");

        // MockMvc, not a real connection: a request the server never answers would otherwise
        // stay open past the end of the run.
        MvcResult result = mvc.perform(post("/mpesa/stkpush/v1/processrequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submission("order-lost", PHONE, null))))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThatThrownBy(() -> result.getAsyncResult(250)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a submission declared BAD_REQUEST answers 400.002.02, the one submission error observed")
    void a_bad_request_answers_the_observed_code() {
        declare("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"BAD_REQUEST"}}}]}""");

        ResponseEntity<String> answer = submit("order-refused", PHONE, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(read(answer).get("errorCode").asText()).isEqualTo("400.002.02");
        assertThat(read(answer).get("errorMessage").asText()).isEqualTo("Bad Request - Invalid CallBackURL");
    }

    // --- the status query -------------------------------------------------------------------

    @Test
    @DisplayName("the default scenario answers ResultCode 0 on the first query -- a modelled success, since none has been observed")
    void the_default_scenario_succeeds() {
        String checkoutRequestId = read(submit("order-happy", PHONE, null, null)).get("CheckoutRequestID").asText();

        JsonNode answer = read(query(checkoutRequestId, null));

        assertThat(answer.get("ResultCode").asInt()).isZero();
        assertThat(answer.get("CheckoutRequestID").asText()).isEqualTo(checkoutRequestId);
    }

    @Test
    @DisplayName("a query on an identity Safaricom never minted answers HTTP 500 with 500.001.1001, as observed")
    void an_unknown_identity_answers_the_observed_500() {
        ResponseEntity<String> answer = query("ws_CO_230920262019330000000000", null);

        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(read(answer).get("errorCode").asText()).isEqualTo("500.001.1001");
        assertThat(read(answer).get("errorMessage").asText()).isEqualTo("The transaction does not Exist");
    }

    @Test
    @DisplayName("the 2026-09-23 run can be declared poll by poll: 4999, then 1037 with 500.001.1001 interleaved, the last entry repeating")
    void the_observed_poll_sequence_is_declarable() {
        declare("""
            {"rules":[{"scenario":{"name":"inflight-run-2026-09-23","onQuery":[
                {"status":"STILL_PROCESSING"},{"status":"STILL_PROCESSING"},
                {"status":"NO_RESPONSE_FROM_USER"},{"error500":true},
                {"status":"NO_RESPONSE_FROM_USER"}]}}]}""");
        String checkoutRequestId = read(submit("order-inflight", PHONE, null, null)).get("CheckoutRequestID").asText();

        JsonNode first = read(query(checkoutRequestId, null));
        assertThat(first.get("ResultCode").asInt()).isEqualTo(4999);
        assertThat(first.get("ResultDesc").asText()).isEqualTo("The transaction is still under processing");
        assertThat(resultCode(query(checkoutRequestId, null))).isEqualTo(4999);
        JsonNode third = read(query(checkoutRequestId, null));
        assertThat(third.get("ResultCode").asInt()).isEqualTo(1037);
        assertThat(third.get("ResultDesc").asText()).isEqualTo("DS timeout user cannot be reached.");

        ResponseEntity<String> fourth = query(checkoutRequestId, null);
        assertThat(fourth.getStatusCode().value()).as("a declared failure on a known payment").isEqualTo(500);
        assertThat(read(fourth).get("errorCode").asText()).isEqualTo("500.001.1001");

        for (int i = 0; i < 3; i++) {
            assertThat(resultCode(query(checkoutRequestId, null))).as("the last entry repeats").isEqualTo(1037);
        }
        assertThat(read(client.get().uri(base() + "/_nkap/state/" + checkoutRequestId).retrieve().toEntity(String.class))
                .get("queryCount").asInt()).as("a failed poll counts as a query").isEqualTo(7);
    }

    @Test
    @DisplayName("a 500 is never answered for a known payment unless a scenario declares it")
    void no_500_unless_declared() {
        String checkoutRequestId = read(submit("order-steady", PHONE, null, null)).get("CheckoutRequestID").asText();

        for (int i = 0; i < 20; i++) {
            assertThat(query(checkoutRequestId, null).getStatusCode().value()).isEqualTo(200);
        }
    }

    // --- the callback -----------------------------------------------------------------------

    @Test
    @DisplayName("the STK callback goes to the submission's CallBackURL in the observed shape, carrying nothing the caller chose")
    void the_callback_has_the_observed_shape() {
        declare("""
            {"rules":[{"scenario":{"callbacks":[{"after":"PT0S","status":"NO_RESPONSE_FROM_USER"}]}}]}""");

        JsonNode submitted = read(submit("order-callback", PHONE, hookUrl(), null));

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.deliveries.size() == 1);
        Hook.Delivery delivery = hook.deliveries.get(0);
        JsonNode callback = delivery.body().get("Body").get("stkCallback");
        assertThat(callback.get("CheckoutRequestID").asText()).isEqualTo(submitted.get("CheckoutRequestID").asText());
        assertThat(callback.get("MerchantRequestID").asText()).isEqualTo(submitted.get("MerchantRequestID").asText());
        assertThat(callback.get("ResultCode").isNumber()).as("ResultCode is a JSON number, observed").isTrue();
        assertThat(callback.get("ResultCode").asInt()).isEqualTo(1037);
        assertThat(callback.get("ResultDesc").asText()).isEqualTo("No response from user.");
        assertThat(delivery.body().toString()).doesNotContain("order-callback");
        assertThat(MediaType.parseMediaType(delivery.contentType()))
                .isEqualTo(MediaType.parseMediaType("application/json;charset=UTF-8"));
        assertThat(delivery.businessShortCode()).isEqualTo("174379");

        List<?> attempts = client.get().uri(base() + "/_nkap/callbacks/" + submitted.get("CheckoutRequestID").asText())
                .retrieve().body(List.class);
        assertThat(attempts).hasSize(1);
    }

    @Test
    @DisplayName("an UNKNOWN_REFERENCE callback names a CheckoutRequestID of Safaricom's shape that no submission was given")
    void an_unknown_reference_callback_has_the_operators_shape() {
        declare("""
            {"rules":[{"scenario":{"callbacks":[{"after":"PT0S","target":"UNKNOWN_REFERENCE",
                                                "status":"NO_RESPONSE_FROM_USER"}]}}]}""");

        String submitted = read(submit("order-stranger", PHONE, hookUrl(), null)).get("CheckoutRequestID").asText();

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.deliveries.size() == 1);
        String named = hook.deliveries.get(0).body().get("Body").get("stkCallback").get("CheckoutRequestID").asText();
        assertThat(named).matches("ws_CO_\\d{24}").isNotEqualTo(submitted);
        assertThat(query(named, null).getStatusCode().value()).as("no payment answers to it").isEqualTo(500);
    }

    // --- the control plane ------------------------------------------------------------------

    @Test
    @DisplayName("M-Pesa's declaration document has no account section, since this face plays no balance or holder read")
    void the_declaration_has_no_account_section() {
        declare("""
            {"callbackUrl":"http://localhost:1/never","rules":[{"scenario":{"name":"x"}}]}""");

        JsonNode declared = read(client.get().uri(base() + "/_nkap/scenarios").retrieve().toEntity(String.class));

        assertThat(declared.has("account")).isFalse();
        assertThat(declared.get("callbackUrl").asText()).isEqualTo("http://localhost:1/never");
        assertThat(declared.get("rules").get(0).get("scenario").get("name").asText()).isEqualTo("x");
    }

    @Test
    @DisplayName("a scenario declaring CONFLICT is refused when declared, naming the face's policy, and replaces nothing")
    void conflict_is_refused_at_declaration() {
        declare("""
            {"rules":[{"scenario":{"name":"kept"}}]}""");

        ResponseEntity<String> refused = client.post().uri(base() + "/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {"rules":[{"scenario":{"name":"fine"}},
                              {"scenario":{"name":"impossible","onSubmit":{"outcome":"CONFLICT"}}}]}""")
                .retrieve().toEntity(String.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        JsonNode body = read(refused);
        assertThat(body.get("error").asText()).isEqualTo("malformed scenario");
        assertThat(body.get("field").asText()).isEqualTo("rules.[1].scenario.onSubmit.outcome");
        assertThat(body.get("detail").asText())
                .contains("does not refuse a repeated reference")
                .contains("NOT_DEDUPLICATED")
                .contains("declared CONFLICT anyway");
        JsonNode active = read(client.get().uri(base() + "/_nkap/scenarios").retrieve().toEntity(String.class));
        assertThat(active.get("rules").get(0).get("scenario").get("name").asText())
                .as("the refused declaration replaced nothing").isEqualTo("kept");
    }

    // --- helpers ----------------------------------------------------------------------------

    private void declare(String document) {
        ResponseEntity<Void> answer = client.post().uri(base() + "/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON).body(document).retrieve().toBodilessEntity();
        assertThat(answer.getStatusCode().value()).as("the scenario was accepted").isEqualTo(204);
    }

    /** The documented example's shape: BusinessShortCode a number, Amount and PartyB strings. */
    private static Map<String, Object> submission(String accountReference, String phone, String callbackUrl) {
        return Map.ofEntries(
                Map.entry("BusinessShortCode", 174379),
                Map.entry("Password", "cGFzc3dvcmQ="),
                Map.entry("Timestamp", "20260923201933"),
                Map.entry("TransactionType", "CustomerPayBillOnline"),
                Map.entry("Amount", "1"),
                Map.entry("PartyA", phone),
                Map.entry("PartyB", "174379"),
                Map.entry("PhoneNumber", phone),
                Map.entry("CallBackURL", callbackUrl != null ? callbackUrl : "https://example.invalid/nkap/mpesa"),
                Map.entry("AccountReference", accountReference),
                Map.entry("TransactionDesc", "test"));
    }

    private ResponseEntity<String> submit(String accountReference, String phone, String callbackUrl, String authorization) {
        var request = client.post().uri(base() + "/mpesa/stkpush/v1/processrequest")
                .contentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.body(submission(accountReference, phone, callbackUrl)).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> query(String checkoutRequestId, String authorization) {
        var request = client.post().uri(base() + "/mpesa/stkpushquery/v1/query")
                .contentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.body(Map.of("BusinessShortCode", 174379, "Password", "cGFzc3dvcmQ=",
                        "Timestamp", "20260923201933", "CheckoutRequestID", checkoutRequestId))
                .retrieve().toEntity(String.class);
    }

    private int resultCode(ResponseEntity<String> answer) {
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        return read(answer).get("ResultCode").asInt();
    }

    private JsonNode read(ResponseEntity<String> answer) {
        try {
            return json.readTree(answer.getBody());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + answer.getBody(), e);
        }
    }

    @TestConfiguration
    static class HookConfig {
        @Bean
        Hook hook(ObjectMapper json) {
            return new Hook(json);
        }
    }

    @RestController
    static class Hook {

        record Delivery(JsonNode body, String contentType, String businessShortCode) {}

        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        private final ObjectMapper json;

        Hook(ObjectMapper json) {
            this.json = json;
        }

        @PostMapping("/test-hook/mpesa")
        void receive(@RequestBody String body,
                     @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                     @RequestHeader(value = "BusinessShortCode", required = false) String businessShortCode)
                throws Exception {
            deliveries.add(new Delivery(json.readTree(body), contentType, businessShortCode));
        }

        void clear() {
            deliveries.clear();
        }
    }
}
