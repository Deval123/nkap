package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.mtn.StubMtn.RecordedRequest;
import dev.nkap.provider.mtn.StubMtn.StubResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MtnTokenCacheTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private MtnProfile profileAt(URI base) {
        return new MtnProfile(base, "sandbox", "sub-key", "api-user", "api-key", Currency.EUR, "sandbox");
    }

    @Test
    @DisplayName("the token call sends Content-Length: 0 and never Transfer-Encoding: chunked")
    void the_token_call_declares_a_zero_length_body() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            AtomicInteger n = new AtomicInteger();
            mtn.respondWith(request -> new StubResponse(200, StubMtn.tokenJson("tok-" + n.incrementAndGet(), 3600)));
            MtnTokenCache cache = new MtnTokenCache(profileAt(mtn.baseUrl()), http, Duration.ofSeconds(5), json);

            cache.bearer();

            RecordedRequest tokenCall = mtn.requests.get(0);
            assertThat(tokenCall.method()).isEqualTo("POST");
            assertThat(tokenCall.path()).isEqualTo("/collection/token/");
            assertThat(tokenCall.header("Content-Length")).isEqualTo("0");
            assertThat(tokenCall.header("Transfer-Encoding")).isNull();
            assertThat(tokenCall.header("Authorization")).startsWith("Basic ");
            assertThat(tokenCall.header("Ocp-Apim-Subscription-Key")).isEqualTo("sub-key");
        }
    }

    @Test
    @DisplayName("a token that is still live is reused, not refetched")
    void a_live_token_is_reused() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> new StubResponse(200, StubMtn.tokenJson("tok-1", 3600)));
            MtnTokenCache cache = new MtnTokenCache(profileAt(mtn.baseUrl()), http, Duration.ofSeconds(5), json);

            String first = cache.bearer();
            String second = cache.bearer();

            assertThat(first).isEqualTo("tok-1").isEqualTo(second);
            assertThat(mtn.countPath("/collection/token/")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a token inside the refresh margin is refetched before it is used, not after it expires")
    void a_token_near_expiry_is_refreshed_ahead_of_time() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            AtomicInteger n = new AtomicInteger();
            mtn.respondWith(request -> new StubResponse(200, StubMtn.tokenJson("tok-" + n.incrementAndGet(), 30)));
            // 100s margin against a 30s token: it is always inside the margin, so always refetched.
            MtnTokenCache cache = new MtnTokenCache(
                    profileAt(mtn.baseUrl()), http, Duration.ofSeconds(5), json, Duration.ofSeconds(100));

            cache.bearer();
            cache.bearer();

            assertThat(mtn.countPath("/collection/token/")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a token endpoint that fails becomes ProviderUnavailableException, never a silent empty token")
    void a_failing_token_endpoint_is_unavailable() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> new StubResponse(503, "<html>Service Unavailable</html>"));
            MtnTokenCache cache = new MtnTokenCache(profileAt(mtn.baseUrl()), http, Duration.ofSeconds(2), json);

            assertThatThrownBy(cache::bearer).isInstanceOf(ProviderUnavailableException.class);
        }
    }

    @Test
    @DisplayName("concurrent callers share one in-flight refresh instead of stampeding")
    void concurrent_callers_share_one_refresh() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            AtomicInteger issued = new AtomicInteger();
            mtn.respondWith(request -> {
                sleep(300);
                return new StubResponse(200, StubMtn.tokenJson("tok-" + issued.incrementAndGet(), 3600));
            });
            MtnTokenCache cache = new MtnTokenCache(profileAt(mtn.baseUrl()), http, Duration.ofSeconds(5), json);

            int callers = 12;
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            CyclicBarrier start = new CyclicBarrier(callers);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.bearer();
                }));
            }
            Set<String> tokens = new HashSet<>();
            for (Future<String> result : results) {
                tokens.add(result.get(10, TimeUnit.SECONDS));
            }
            pool.shutdownNow();

            assertThat(tokens).containsExactly("tok-1");
            assertThat(mtn.countPath("/collection/token/")).isEqualTo(1);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
