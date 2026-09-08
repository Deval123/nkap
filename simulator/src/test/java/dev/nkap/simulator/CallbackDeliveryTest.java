package dev.nkap.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.nkap.simulator.scenario.MomoStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Callback delivery, end to end against a real receiver. The simulator runs on a
 * real port here because a callback is a real outbound HTTP request; MockMvc
 * would not see it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CallbackDeliveryTest {

    private static final String BODY = """
        {"amount":"5000","currency":"XAF",
         "payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}""";

    @LocalServerPort
    int port;

    @Autowired
    Hook hook;

    @Autowired
    CallbackDispatcher dispatcher;

    private RestClient client;

    private String base() {
        return "http://localhost:" + port;
    }

    private String hookUrl() {
        return base() + "/test-hook";
    }

    @BeforeEach
    @AfterEach
    void reset() {
        client = RestClient.create();
        client.delete().uri(base() + "/_nkap/scenarios").retrieve().toBodilessEntity();
        client.delete().uri(base() + "/_nkap/state").retrieve().toBodilessEntity();
        hook.clear();
    }

    private void declare(String json) {
        client.post().uri(base() + "/_nkap/scenarios")
            .contentType(MediaType.APPLICATION_JSON).body(json)
            .retrieve().toBodilessEntity();
    }

    private void submit(String reference, String callbackUrlHeader) {
        var request = client.post().uri(base() + "/collection/v1_0/requesttopay")
            .header("X-Reference-Id", reference)
            .contentType(MediaType.APPLICATION_JSON);
        if (callbackUrlHeader != null) {
            request = request.header("X-Callback-Url", callbackUrlHeader);
        }
        request.body(BODY).retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attempts(String reference) {
        return client.get().uri(base() + "/_nkap/callbacks/" + reference)
            .retrieve().body(List.class);
    }

    @Test
    @DisplayName("a scenario callback is delivered to the X-Callback-Url, shaped like MTN's")
    void callback_is_delivered_to_the_header_url() {
        declare("""
            {"rules":[{"scenario":{"name":"one-callback",
                                   "callbacks":[{"after":"PT0S","status":"SUCCESSFUL"}]}}]}""");
        String ref = UUID.randomUUID().toString();

        submit(ref, hookUrl());

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.bodies.size() == 1);
        Map<String, Object> callback = hook.bodies.get(0);
        assertThat(callback).containsEntry("referenceId", ref);
        assertThat(callback).containsEntry("status", "SUCCESSFUL");
        assertThat(callback).containsEntry("amount", "5000");
        assertThat(callback).containsEntry("currency", "XAF");
        assertThat(callback).containsKey("financialTransactionId");

        assertThat(attempts(ref)).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("targetReferenceId", ref);
            assertThat(a).containsEntry("answered", true);
            assertThat(a).containsEntry("responseStatus", 200);
        });
    }

    @Test
    @DisplayName("a callback declared with times and every is delivered that many times, that far apart")
    void repeated_callbacks_honour_the_interval() {
        declare("""
            {"rules":[{"scenario":{"name":"duplicate-callback",
                                   "callbacks":[{"after":"PT0S","every":"PT1S","times":2,"status":"SUCCESSFUL"}]}}]}""");
        String ref = UUID.randomUUID().toString();

        submit(ref, hookUrl());

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.nanos.size() == 2);
        long gapMillis = (hook.nanos.get(1) - hook.nanos.get(0)) / 1_000_000;
        assertThat(gapMillis).isBetween(700L, 2500L);
        assertThat(attempts(ref)).hasSize(2);
    }

    @Test
    @DisplayName("issue #6: a callback with a zero delay arrives before the delayed submit response")
    void callback_arrives_before_the_submit_response() {
        declare("""
            {"rules":[{"scenario":{"name":"callback-before-response",
                                   "onSubmit":{"delay":"PT2S","outcome":"ACCEPT"},
                                   "callbacks":[{"after":"PT0S","status":"SUCCESSFUL"}]}}]}""");
        String ref = UUID.randomUUID().toString();

        CompletableFuture<Void> submitting = CompletableFuture.runAsync(() -> submit(ref, hookUrl()));

        // The callback lands well inside the two-second submit delay.
        await().atMost(Duration.ofSeconds(1)).until(() -> hook.bodies.size() == 1);
        assertThat(submitting).isNotCompleted();

        submitting.join();
    }

    @Test
    @DisplayName("issue #7: an UNKNOWN_REFERENCE callback carries a reference that was never submitted")
    void unknown_reference_callback_uses_a_fresh_uuid() {
        declare("""
            {"rules":[{"scenario":{"name":"callback-unknown-reference",
                                   "callbacks":[{"after":"PT0S","target":"UNKNOWN_REFERENCE","status":"SUCCESSFUL"}]}}]}""");
        String ref = UUID.randomUUID().toString();

        submit(ref, hookUrl());

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.bodies.size() == 1);
        String delivered = (String) hook.bodies.get(0).get("referenceId");
        assertThat(delivered).isNotEqualTo(ref);
        assertThat(UUID.fromString(delivered)).isNotNull();
        assertThat(attempts(ref)).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("targetReferenceId", delivered));
    }

    @Test
    @DisplayName("the X-Callback-Url header beats the callbackUrl declared in the control plane")
    void header_url_wins_over_declared_url() {
        declare("""
            {"callbackUrl":"http://localhost:1/never",
             "rules":[{"scenario":{"name":"cb","callbacks":[{"after":"PT0S","status":"SUCCESSFUL"}]}}]}""");
        String ref = UUID.randomUUID().toString();

        submit(ref, hookUrl());

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.bodies.size() == 1);
        assertThat(attempts(ref)).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("url", hookUrl()));
    }

    @Test
    @DisplayName("the declared callbackUrl is used when the submit request carries no header")
    void declared_url_is_the_fallback() {
        declare("""
            {"callbackUrl":"%s",
             "rules":[{"scenario":{"name":"cb","callbacks":[{"after":"PT0S","status":"SUCCESSFUL"}]}}]}"""
            .formatted(hookUrl()));
        String ref = UUID.randomUUID().toString();

        submit(ref, null);

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.bodies.size() == 1);
        assertThat(attempts(ref)).hasSize(1);
    }

    @Test
    @DisplayName("with no callback URL anywhere, nothing is sent and the submission still succeeds")
    void no_url_means_no_callback_and_no_error() {
        declare("""
            {"rules":[{"scenario":{"name":"cb","callbacks":[{"after":"PT0S","status":"SUCCESSFUL"}]}}]}""");
        String ref = UUID.randomUUID().toString();

        submit(ref, null);

        assertThat(attempts(ref)).isEmpty();
        assertThat(hook.bodies).isEmpty();
    }

    @Test
    @DisplayName("a FAILED callback carries a reason; DELETE /_nkap/state clears the attempt log")
    void failed_callback_has_reason_and_state_reset_clears_attempts() {
        declare("""
            {"rules":[{"scenario":{"name":"cb","callbacks":[{"after":"PT0S","status":"FAILED"}]}}]}""");
        String ref = UUID.randomUUID().toString();

        submit(ref, hookUrl());

        await().atMost(Duration.ofSeconds(5)).until(() -> hook.bodies.size() == 1);
        assertThat(hook.bodies.get(0)).containsEntry("status", "FAILED").containsKey("reason");

        client.delete().uri(base() + "/_nkap/state").retrieve().toBodilessEntity();
        assertThat(attempts(ref)).isEmpty();
    }

    @Test
    @DisplayName("the attempt log for a single reference is capped at MAX_ATTEMPTS_PER_REFERENCE")
    void attempts_for_single_reference_are_capped() {
        String ref = UUID.randomUUID().toString();
        for (int i = 0; i < CallbackDispatcher.MAX_ATTEMPTS_PER_REFERENCE + 15; i++) {
            dispatcher.recordAttempt(ref, new CallbackDispatcher.Attempt(
                    Instant.now(), hookUrl(), ref, MomoStatus.SUCCESSFUL, true, 200, null));
        }

        assertThat(dispatcher.attemptsFor(ref)).hasSize(CallbackDispatcher.MAX_ATTEMPTS_PER_REFERENCE);
    }

    @Test
    @DisplayName("retained references are bounded at MAX_REFERENCES, oldest evicted first")
    void retained_references_are_bounded_with_oldest_evicted() {
        String oldestRef = "ref-oldest-" + UUID.randomUUID();
        dispatcher.recordAttempt(oldestRef, new CallbackDispatcher.Attempt(
                Instant.now(), hookUrl(), oldestRef, MomoStatus.SUCCESSFUL, true, 200, null));

        for (int i = 0; i < CallbackDispatcher.MAX_REFERENCES; i++) {
            String ref = "ref-" + i + "-" + UUID.randomUUID();
            dispatcher.recordAttempt(ref, new CallbackDispatcher.Attempt(
                    Instant.now(), hookUrl(), ref, MomoStatus.SUCCESSFUL, true, 200, null));
        }

        // Oldest reference should have been evicted
        assertThat(dispatcher.attemptsFor(oldestRef)).isEmpty();
    }

    @TestConfiguration
    static class HookConfig {
        @Bean
        Hook hook() {
            return new Hook();
        }
    }

    @RestController
    static class Hook {
        final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();
        final List<Long> nanos = new CopyOnWriteArrayList<>();

        @PostMapping("/test-hook")
        void receive(@RequestBody Map<String, Object> body) {
            bodies.add(body);
            nanos.add(System.nanoTime());
        }

        void clear() {
            bodies.clear();
            nanos.clear();
        }
    }
}
