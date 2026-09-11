package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.support.PostgresSpringBootIT;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * No metric label carries a credential or an MSISDN (issue #75's plan, "what not to
 * instrument"). Asserted over the live {@link MeterRegistry} after real traffic has gone
 * through the app — including a call that puts an MSISDN in the URL path — rather than by
 * reading the code, which would only prove what this class intended, not what Micrometer's
 * own auto-instrumentation (HTTP server requests, the JDBC pool) actually emits.
 */
class MetricsSurfaceIT extends PostgresSpringBootIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();

    /** A phone-number shape: 8-15 digits, an optional leading +. Nothing legitimate in a label should match this. */
    private static final Pattern MSISDN_SHAPED = Pattern.compile("\\+?\\d{8,15}");

    private static final String SUBSCRIPTION_KEY = "test-subscription-key";
    private static final String API_USER = "test-api-user";
    private static final String API_KEY = "test-api-key";
    private static final String MSISDN = "46733123453";

    @DynamicPropertySource
    static void mtnPointsAtTheSimulator(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> SUBSCRIPTION_KEY);
        registry.add("nkap.provider.mtn.api-user", () -> API_USER);
        registry.add("nkap.provider.mtn.api-key", () -> API_KEY);
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
    MeterRegistry registry;

    @Autowired
    ApiKeyStore apiKeys;

    private String adminKey;
    private String merchantKey;

    @BeforeEach
    void setUp() {
        SIMULATOR.reset();
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "MetricsSurfaceIT").token();
        merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "MetricsSurfaceIT").token();
    }

    @Test
    @DisplayName("after real traffic, including a call with an MSISDN in the path, no meter tag is a credential or an MSISDN")
    void no_tag_anywhere_in_the_registry_is_a_credential_or_an_msisdn() {
        SIMULATOR.declareScenario("""
                {"account":{"balance":{"availableBalance":"10.00","currency":"EUR"},
                            "holder":{"active":true}}}""");
        get("/balance?operation=COLLECT&currency=EUR", adminKey);
        get("/account-holders/" + MSISDN + "?operation=COLLECT", merchantKey);

        for (Meter meter : registry.getMeters()) {
            if (meter.getId().getName().startsWith("http.client.requests")) {
                // TestRestTemplate is itself an auto-instrumented Spring HTTP client, and
                // this test calls it with a literal path rather than a URI template, so its
                // own "uri" tag is the resolved URL, MSISDN included. That is a property of
                // TestRestTemplate calling the app under test, not of anything the
                // application emits: MtnCollectionsAdapter and MtnDisbursementsAdapter use
                // the JDK's own java.net.http.HttpClient, which Micrometer does not
                // instrument, so no http.client.requests meter exists in a real deployment
                // at all. Scanning it here would fail this test over a fact about the test
                // harness, not about this application's registry.
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getKey())
                        .as("meter %s tag key", meter.getId().getName())
                        .doesNotContainIgnoringCase("msisdn")
                        .doesNotContainIgnoringCase("key")
                        .doesNotContainIgnoringCase("token")
                        .doesNotContainIgnoringCase("credential");
                assertThat(tag.getValue())
                        .as("meter %s tag %s", meter.getId().getName(), tag.getKey())
                        .isNotEqualTo(SUBSCRIPTION_KEY)
                        .isNotEqualTo(API_USER)
                        .isNotEqualTo(API_KEY)
                        .isNotEqualTo(adminKey)
                        .isNotEqualTo(merchantKey);
                assertThat(MSISDN_SHAPED.matcher(tag.getValue()).find())
                        .as("meter %s tag %s='%s' looks like a phone number", meter.getId().getName(), tag.getKey(), tag.getValue())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("the HTTP request metric for the holder-check tags the URI template, not the resolved MSISDN")
    void the_http_metric_tags_the_template_not_the_value() {
        SIMULATOR.declareScenario("""
                {"account":{"holder":{"active":true}}}""");
        get("/account-holders/" + MSISDN + "?operation=COLLECT", merchantKey);

        // >= 1, not ==: @SpringBootTest reuses one context (and one registry) across every
        // test method in this class, so an earlier test's call to the same route is still
        // on this counter. What matters is that the tag is the template at all.
        assertThat(registry.get("http.server.requests").tags("uri", "/account-holders/{msisdn}").timer().count())
                .as("Spring's own URI tagging must have collapsed the real MSISDN into its path template")
                .isGreaterThanOrEqualTo(1);
    }

    private void get(String path, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        http.exchange(path, org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
