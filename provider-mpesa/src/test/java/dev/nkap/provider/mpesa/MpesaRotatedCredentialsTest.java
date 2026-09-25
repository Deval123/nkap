package dev.nkap.provider.mpesa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.SubmitResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An adapter built from a supplier of profiles: a rotated credential is used on the next request
 * without a new adapter, and nothing but the credentials follows the supplier.
 */
class MpesaRotatedCredentialsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final AtomicReference<MpesaProfile> current = new AtomicReference<>();
    private HttpServer server;
    private URI base;
    private MpesaAdapter adapter;

    record Recorded(String path, String authorization, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Recorded(path, exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String answer = path.equals("/oauth/v1/generate")
                    ? "{\"access_token\":\"token-" + tokensIssued.incrementAndGet() + "\",\"expires_in\":\"3599\"}"
                    : "{\"MerchantRequestID\":\"m\",\"CheckoutRequestID\":\"ws_CO_1\",\"ResponseCode\":\"0\"}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = URI.create("http://localhost:" + server.getAddress().getPort());
        current.set(profile(base, "174379", "passkey-1", "key-1", "secret-1", Currency.KES));
        adapter = new MpesaAdapter(ProviderId.of("mpesa-ke"), current::get, Duration.ofSeconds(2),
                MpesaTokenCache.DEFAULT_REFRESH_MARGIN,
                Clock.fixed(Instant.parse("2026-09-23T17:19:33Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("a rotated passkey is hashed into the very next request's Password, without a new adapter")
    void a_rotated_passkey_is_used_on_the_next_request() throws Exception {
        submit();
        current.set(profile(base, "174379", "passkey-2", "key-1", "secret-1", Currency.KES));
        submit();

        List<String> passwords = submissions().stream().map(this::password).toList();
        assertThat(passwords).containsExactly("174379passkey-120260923201933", "174379passkey-220260923201933");
    }

    @Test
    @DisplayName("a changed passkey alone keeps the bearer token: it was not obtained with the passkey")
    void a_changed_passkey_alone_keeps_the_token() throws Exception {
        submit();
        current.set(profile(base, "174379", "passkey-2", "key-1", "secret-1", Currency.KES));
        submit();

        assertThat(tokensIssued).hasValue(1);
        assertThat(submissions()).extracting(Recorded::authorization).containsOnly("Bearer token-1");
    }

    @Test
    @DisplayName("a changed Consumer Key drops the token obtained with the old pair, and the next one is fetched with the new pair")
    void a_changed_consumer_key_drops_the_token() throws Exception {
        submit();
        current.set(profile(base, "174379", "passkey-1", "key-2", "secret-1", Currency.KES));
        submit();

        assertThat(tokensIssued).hasValue(2);
        assertThat(tokenCalls()).extracting(Recorded::authorization)
                .containsExactly(basic("key-1", "secret-1"), basic("key-2", "secret-1"));
        assertThat(submissions()).extracting(Recorded::authorization)
                .containsExactly("Bearer token-1", "Bearer token-2");
    }

    @Test
    @DisplayName("a changed Consumer Secret drops the token obtained with the old pair")
    void a_changed_consumer_secret_drops_the_token() throws Exception {
        submit();
        current.set(profile(base, "174379", "passkey-1", "key-1", "secret-2", Currency.KES));
        submit();

        assertThat(tokensIssued).hasValue(2);
        assertThat(tokenCalls()).extracting(Recorded::authorization).last().isEqualTo(basic("key-1", "secret-2"));
    }

    @Test
    @DisplayName("a later profile's base URL, shortcode and currency are ignored: routing was fixed from the first")
    void only_credentials_follow_the_supplier() throws Exception {
        current.set(profile(URI.create("http://elsewhere.invalid"), "600000", "passkey-2", "key-1", "secret-1",
                Currency.EUR));

        SubmitResult result = submit();

        assertThat(result).isInstanceOf(SubmitResult.Acknowledged.class);
        JsonNode sent = json.readTree(submissions().get(0).body());
        assertThat(sent.get("BusinessShortCode").asLong()).isEqualTo(174379);
        assertThat(password(submissions().get(0))).isEqualTo("174379passkey-220260923201933");
    }

    @Test
    @DisplayName("the constructors that take a profile keep it for the adapter's life, as before")
    void a_profile_constructor_is_fixed() throws Exception {
        MpesaProfile fixed = profile(base, "174379", "passkey-1", "key-1", "secret-1", Currency.KES);
        MpesaAdapter byProfile = new MpesaAdapter(ProviderId.of("mpesa-ke"), fixed, Duration.ofSeconds(2),
                MpesaTokenCache.DEFAULT_REFRESH_MARGIN,
                Clock.fixed(Instant.parse("2026-09-23T17:19:33Z"), ZoneOffset.UTC));
        current.set(profile(base, "174379", "passkey-2", "key-2", "secret-2", Currency.KES));

        byProfile.submit(intent(), ReferenceId.newReference());
        byProfile.submit(intent(), ReferenceId.newReference());

        assertThat(tokensIssued).hasValue(1);
        assertThat(submissions()).extracting(this::password).containsOnly("174379passkey-120260923201933");
    }

    private SubmitResult submit() throws Exception {
        return adapter.submit(intent(), ReferenceId.newReference());
    }

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(100, Currency.KES), "254708374149",
                "pay", "note", Map.of("callbackUrl", "https://gw.example/cb/1"));
    }

    private static MpesaProfile profile(URI base, String shortCode, String passkey, String key, String secret,
                                        Currency currency) {
        return new MpesaProfile(base, shortCode, passkey, key, secret, currency);
    }

    private List<Recorded> submissions() {
        return requests.stream().filter(r -> r.path().equals("/mpesa/stkpush/v1/processrequest")).toList();
    }

    private List<Recorded> tokenCalls() {
        return requests.stream().filter(r -> r.path().equals("/oauth/v1/generate")).toList();
    }

    private String password(Recorded submission) {
        try {
            String encoded = json.readTree(submission.body()).get("Password").asText();
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String basic(String key, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
