package dev.nkap.provider.mpesa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the conformance kit does not reach: the request exactly as it goes on the wire, the
 * answers only a stub can give on demand, and the refusals this adapter makes before sending
 * anything. A plain JDK HTTP server stands in for Safaricom and records every request.
 */
class MpesaAdapterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Answer> submitAnswer = new AtomicReference<>();
    private final AtomicReference<Answer> queryAnswer = new AtomicReference<>();
    private HttpServer server;
    private MpesaAdapter adapter;

    record Recorded(String path, String body) {}

    record Answer(int status, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(path, body));
            Answer answer = switch (path) {
                case "/oauth/v1/generate" -> new Answer(200, "{\"access_token\":\"abcdefghijklmnopqrstuvwxyz12\",\"expires_in\":\"3599\"}");
                case "/mpesa/stkpush/v1/processrequest" -> submitAnswer.get();
                case "/mpesa/stkpushquery/v1/query" -> queryAnswer.get();
                default -> new Answer(404, "{}");
            };
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        submitAnswer.set(new Answer(200, """
            {"MerchantRequestID":"5dbd-4f93-a478-41cdaf2b9acd86873",
             "CheckoutRequestID":"ws_CO_230920262019337708374149","ResponseCode":"0"}"""));
        MpesaProfile profile = new MpesaProfile(URI.create("http://localhost:" + server.getAddress().getPort()),
                "174379", "passkey", "key", "secret", Currency.KES);
        adapter = new MpesaAdapter(ProviderId.of("mpesa"), profile, Duration.ofSeconds(2),
                MpesaTokenCache.DEFAULT_REFRESH_MARGIN,
                Clock.fixed(Instant.parse("2026-09-23T17:19:33Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("a submission is sent with the documented example's types: BusinessShortCode a number, Amount and PartyB strings")
    void a_submission_keeps_safaricoms_inconsistent_types() throws Exception {
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult result = adapter.submit(intent(Money.of(100, Currency.KES), "https://gw.example/cb/1"), reference);

        assertThat(result).isInstanceOfSatisfying(SubmitResult.Acknowledged.class, acknowledged -> {
            assertThat(acknowledged.state()).isEqualTo(PaymentState.SUBMITTED);
            assertThat(acknowledged.providerReference()).isEqualTo("ws_CO_230920262019337708374149");
        });
        JsonNode sent = json.readTree(sent("/mpesa/stkpush/v1/processrequest"));
        assertThat(sent.get("BusinessShortCode").isNumber()).isTrue();
        assertThat(sent.get("BusinessShortCode").asLong()).isEqualTo(174379);
        assertThat(sent.get("PartyB").isTextual()).isTrue();
        assertThat(sent.get("PartyB").asText()).isEqualTo("174379");
        assertThat(sent.get("Amount").isTextual()).isTrue();
        assertThat(sent.get("Amount").asText()).isEqualTo("1");
        assertThat(sent.get("CallBackURL").asText()).isEqualTo("https://gw.example/cb/1");
        assertThat(sent.get("AccountReference").asText()).isEqualTo(reference.toString());
        // 20:19:33 in Nairobi for 17:19:33Z, and the password is base64(shortcode + passkey + timestamp).
        assertThat(sent.get("Timestamp").asText()).isEqualTo("20260923201933");
        assertThat(new String(java.util.Base64.getDecoder().decode(sent.get("Password").asText()), StandardCharsets.UTF_8))
                .isEqualTo("174379passkey20260923201933");
    }

    @Test
    @DisplayName("a fractional shilling is not attempted, and nothing is sent")
    void a_fractional_amount_is_not_attempted() throws Exception {
        SubmitResult result = adapter.submit(intent(Money.of(150, Currency.KES), "https://gw.example/cb"),
                ReferenceId.newReference());

        assertThat(result).isInstanceOf(SubmitResult.NotAttempted.class);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a submission with no callbackUrl is not attempted, and nothing is sent")
    void no_callback_url_is_not_attempted() throws Exception {
        SubmitResult result = adapter.submit(intent(Money.of(100, Currency.KES), null), ReferenceId.newReference());

        assertThat(result).isInstanceOf(SubmitResult.NotAttempted.class);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a 400 is Rejected with the operator's own errorCode, as 400.002.02 was observed")
    void a_400_is_rejected_with_the_error_code() throws Exception {
        submitAnswer.set(new Answer(400, "{\"errorCode\":\"400.002.02\",\"errorMessage\":\"Bad Request - Invalid CallBackURL\"}"));

        SubmitResult result = adapter.submit(intent(Money.of(100, Currency.KES), "https://gw.example/cb"),
                ReferenceId.newReference());

        assertThat(result).isInstanceOfSatisfying(SubmitResult.Rejected.class, rejected -> {
            assertThat(rejected.providerCode()).isEqualTo("400.002.02");
            assertThat(rejected.reason()).isEqualTo("Bad Request - Invalid CallBackURL");
        });
    }

    @Test
    @DisplayName("a 200 naming no CheckoutRequestID is no answer: nothing could ever ask about that payment")
    void an_acknowledgement_without_an_identity_is_unavailable() {
        submitAnswer.set(new Answer(200, "{\"ResponseCode\":\"0\"}"));

        assertThatThrownBy(() -> adapter.submit(intent(Money.of(100, Currency.KES), "https://gw.example/cb"),
                ReferenceId.newReference()))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    @DisplayName("500.001.1001 on a query is no answer, never a status: it distinguishes nothing")
    void the_indistinct_500_is_unavailable() {
        queryAnswer.set(new Answer(500, "{\"errorCode\":\"500.001.1001\",\"errorMessage\":\"The transaction does not Exist\"}"));

        assertThatThrownBy(() -> adapter.query(
                new QuerySubject(ReferenceId.newReference(), "ws_CO_230920262019337708374149"), Capability.Operation.COLLECT))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("500.001.1001");
    }

    @Test
    @DisplayName("a query without a CheckoutRequestID raises no-answer and sends nothing")
    void a_blank_provider_reference_sends_nothing() {
        assertThatThrownBy(() -> adapter.query(QuerySubject.of(ReferenceId.newReference()), Capability.Operation.COLLECT))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a ResultCode sent as a string is read the same as a number")
    void a_string_result_code_is_read() throws Exception {
        queryAnswer.set(new Answer(200, "{\"ResultCode\":\"4999\",\"ResultDesc\":\"The transaction is still under processing\"}"));

        ProviderStatus status = adapter.query(
                new QuerySubject(ReferenceId.newReference(), "ws_CO_230920262019337708374149"), Capability.Operation.COLLECT);

        assertThat(status.state()).isEqualTo(PaymentState.PENDING);
        assertThat(status.providerStatusCode()).isEqualTo("4999");
    }

    @Test
    @DisplayName("the observed callback body parses to an unattributed event naming the CheckoutRequestID, 1037 as FAILED")
    void the_observed_callback_parses() throws Exception {
        String body = """
            {"Body":{"stkCallback":{"MerchantRequestID":"5dbd-4f93-a478-41cdaf2b9acd86873",
             "CheckoutRequestID":"ws_CO_220920261335362708374149","ResultCode":1037,
             "ResultDesc":"No response from user."}}}""";

        CallbackEvent event = adapter.parseCallback(new RawCallback(Map.of("Businessshortcode", "174379"), body));

        assertThat(event.reference()).as("nothing Nkap chose is in the body").isNull();
        assertThat(event.providerReference()).isEqualTo("ws_CO_220920261335362708374149");
        assertThat(event.status().state()).isEqualTo(PaymentState.FAILED);
        assertThat(event.status().providerStatusCode()).isEqualTo("1037");
    }

    @Test
    @DisplayName("a callback without a readable ResultCode is untrusted")
    void a_callback_without_a_result_code_is_untrusted() {
        assertThatThrownBy(() -> adapter.parseCallback(new RawCallback(Map.of(),
                "{\"Body\":{\"stkCallback\":{\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":\"soon\"}}}")))
                .isInstanceOf(UntrustedCallbackException.class);
    }

    private static PaymentIntent intent(Money amount, String callbackUrl) {
        return new PaymentIntent(Capability.Operation.COLLECT, amount, "254708374149", "pay", "note",
                callbackUrl == null ? Map.of() : Map.of("callbackUrl", callbackUrl));
    }

    private String sent(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).findFirst().orElseThrow().body();
    }
}
