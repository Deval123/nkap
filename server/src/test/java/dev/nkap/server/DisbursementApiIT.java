package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.reconcile.Reconciler;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Disbursements, end to end: a real server, a real MTN adapter (its Disbursements product),
 * a real simulator over HTTP, a real PostgreSQL, and — the point of issue #62 — the
 * <strong>same</strong> reconciler and the same settlement path as collections.
 *
 * <p>Not a {@code PostgresSpringBootIT}: that base turns the reconciler off, and two of
 * these tests are about the reconciler resolving a disbursement. The reconciler is on but
 * its scheduled interval is an hour, so the only pass that runs is the one a test calls by
 * hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(DockerAvailable.class)
class DisbursementApiIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        PostgresDatabase db = PostgresDatabase.shared();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("nkap.provider.mtn.base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> "collection-sub-key");
        registry.add("nkap.provider.mtn.api-user", () -> "collection-user");
        registry.add("nkap.provider.mtn.api-key", () -> "collection-key");
        registry.add("nkap.provider.mtn.currency", () -> "EUR");
        registry.add("nkap.provider.mtn.country", () -> "sandbox");
        registry.add("nkap.provider.mtn.request-timeout", () -> "PT2S");
        // The Disbursements product's own credentials — the thing MTN makes different.
        registry.add("nkap.provider.mtn.disbursement.subscription-key", () -> "disbursement-sub-key");
        registry.add("nkap.provider.mtn.disbursement.api-user", () -> "disbursement-user");
        registry.add("nkap.provider.mtn.disbursement.api-key", () -> "disbursement-key");

        // Reconciler on, but only when a test asks for a pass.
        registry.add("nkap.reconciler.enabled", () -> "true");
        registry.add("nkap.reconciler.interval", () -> "PT1H");
        registry.add("nkap.reconciler.batch-size", () -> "50");
        registry.add("nkap.reconciler.backoff-base", () -> "PT1S");
        registry.add("nkap.reconciler.backoff-max", () -> "PT1S");
        registry.add("nkap.reconciler.window", () -> "PT24H");
    }

    @AfterAll
    static void stopSimulator() {
        SIMULATOR.close();
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    Ledger ledger;

    @Autowired
    Reconciler reconciler;

    @Autowired
    ApiKeyStore apiKeys;

    private String apiKey;

    @BeforeEach
    void setUp() {
        SIMULATOR.reset();
        apiKey = apiKeys.provision("merchant-1", false, "DisbursementApiIT").token();
    }

    @Test
    @DisplayName("a disbursement that settles posts the ADR 0007 mirror: DR merchant payable / CR provider float, summing to zero")
    void a_settled_disbursement_posts_the_mirror_entry() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"ACCEPT"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");

        ResponseEntity<String> created = postDisbursement(5000);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String reference = field(created, "reference");
        assertThat(field(created, "state")).isEqualTo("SUBMITTED");

        // The reconciler — unchanged — queries and settles it, exactly as for a collection.
        reconciler.runOnce();

        JsonNode payment = getDisbursement(reference);
        assertThat(payment.get("state").asText()).isEqualTo("SUCCEEDED");

        assertThat(ledger.entriesForReference(reference)).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("disbursement:" + reference);
            assertMirrorPostings(entry);
        });
    }

    @Test
    @DisplayName("a disbursement that times out is 202 UNKNOWN, and the same reconciler resolves it to SUCCEEDED")
    void a_timed_out_disbursement_is_resolved_by_the_reconciler() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"},
                                   "onQuery":[{"status":"PENDING","reason":"SERVICE_UNAVAILABLE"},
                                              {"status":"SUCCESSFUL"}]}}]}""");

        ResponseEntity<String> created = postDisbursement(7000);
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        String reference = field(created, "reference");
        assertThat(field(created, "state")).isEqualTo("UNKNOWN");
        assertThat(ledger.entriesForReference(reference)).isEmpty();

        reconciler.runOnce();   // first re-query: SERVICE_UNAVAILABLE -> still UNKNOWN, nothing written
        assertThat(getDisbursement(reference).get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(ledger.entriesForReference(reference)).isEmpty();

        makeDue(reference);
        reconciler.runOnce();   // next re-query: SUCCESSFUL -> settled

        JsonNode payment = getDisbursement(reference);
        assertThat(payment.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(payment.get("history")).anySatisfy(t -> {
            assertThat(t.get("to").asText()).isEqualTo("SUCCEEDED");
            assertThat(t.get("cause").asText()).isEqualTo("RECONCILER");
        });
        assertThat(ledger.entriesForReference(reference)).singleElement()
                .satisfies(DisbursementApiIT::assertMirrorPostings);
    }

    @Test
    @DisplayName("a callback for a disbursement settles it, confirmed by query like any other")
    void a_callback_settles_a_disbursement() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"ACCEPT"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");

        String reference = field(postDisbursement(9000), "reference");

        // The callback endpoint takes no credential; it triggers a confirming query.
        ResponseEntity<String> callbackAnswer = http.postForEntity("/callbacks/mtn",
                new HttpEntity<>("{\"referenceId\":\"" + reference + "\",\"status\":\"SUCCESSFUL\"}", jsonHeaders(null)),
                String.class);
        assertThat(callbackAnswer.getStatusCode().value()).isEqualTo(202);

        JsonNode payment = getDisbursement(reference);
        assertThat(payment.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(payment.get("history")).anySatisfy(t -> {
            assertThat(t.get("to").asText()).isEqualTo("SUCCEEDED");
            assertThat(t.get("cause").asText()).isEqualTo("CALLBACK");
        });
        assertThat(ledger.entriesForReference(reference)).singleElement()
                .satisfies(DisbursementApiIT::assertMirrorPostings);
    }

    // --- helpers -------------------------------------------------------------------

    private static void assertMirrorPostings(LedgerEntry entry) {
        assertThat(entry.postings()).hasSize(2);
        assertThat(signed(entry, AccountId.providerFloat("mtn", Currency.EUR)))
                .as("the float goes down — money left it").isNegative();
        assertThat(signed(entry, AccountId.merchantPayable("merchant-1", Currency.EUR)))
                .as("we owe the merchant less").isPositive();
        long sum = entry.postings().stream().mapToLong(p -> p.amount().amount()).sum();
        assertThat(sum).as("balances to zero").isZero();
    }

    private static long signed(LedgerEntry entry, AccountId account) {
        return entry.postings().stream().filter(p -> p.account().equals(account))
                .mapToLong(p -> p.amount().amount()).sum();
    }

    private ResponseEntity<String> postDisbursement(long amount) {
        String body = """
            {"operation":"DISBURSE","amount":%d,"currency":"EUR",
             "counterpartyMsisdn":"46733123453","payerMessage":"payout","payeeNote":"payout"}"""
                .formatted(amount);
        HttpHeaders headers = jsonHeaders(apiKey);
        headers.set("Idempotency-Key", java.util.UUID.randomUUID().toString());
        return http.postForEntity("/payments", new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode getDisbursement(String reference) {
        ResponseEntity<String> response = http.exchange("/payments/" + reference, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(apiKey)), String.class);
        return parse(response.getBody());
    }

    private void makeDue(String reference) {
        // The reconciler advanced the schedule after its first pass; bring it back so the
        // next runOnce() claims the payment again. Uses the shared database directly.
        PostgresDatabase.shared().jdbcTemplate().update(
                "UPDATE payment SET reconcile_due_at = now() - interval '1 hour' WHERE reference = ?::uuid", reference);
    }

    private HttpHeaders jsonHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    private String field(ResponseEntity<String> response, String name) {
        return parse(response.getBody()).get(name).asText();
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + body, e);
        }
    }
}
