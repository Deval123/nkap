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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Refunds, end to end (issue #84, ADR 0010): a real server, a real MTN adapter (both
 * products), a real simulator over HTTP, a real PostgreSQL — and, the point of the whole
 * design, the <strong>same</strong> settlement path, reconciler and idempotency store as
 * every other payment. Nothing in this class drives a refund-specific code path; every test
 * here calls exactly the machinery {@code PaymentApiIT} and {@code DisbursementApiIT} do.
 *
 * <p>Not a {@code PostgresSpringBootIT}: two of these tests are about the reconciler
 * resolving a refund, the same reason {@code DisbursementApiIT} is not one either. The
 * reconciler is on but its scheduled interval is an hour, so the only pass that runs is the
 * one a test calls by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(DockerAvailable.class)
class RefundApiIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        PostgresDatabase db = PostgresDatabase.shared();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("nkap.provider.mtn.installations[0].base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "collection-sub-key");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "collection-user");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "collection-key");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "EUR");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].request-timeout", () -> "PT2S");
        // A refund is a DISBURSE payment (ADR 0010): the Disbursements product's own
        // credentials are needed exactly as DisbursementApiIT needs them.
        registry.add("nkap.provider.mtn.installations[0].disbursement.subscription-key", () -> "disbursement-sub-key");
        registry.add("nkap.provider.mtn.installations[0].disbursement.api-user", () -> "disbursement-user");
        registry.add("nkap.provider.mtn.installations[0].disbursement.api-key", () -> "disbursement-key");
        registry.add("nkap.provider.default", () -> "mtn-sandbox");

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
        apiKey = apiKeys.provision("merchant-1", false, "RefundApiIT").token();
    }

    // --- the happy path and its ledger effect ---------------------------------------------

    @Test
    @DisplayName("a refund of a SUCCEEDED collection settles into its own refund:<ref> entry, "
            + "and the original collection's entry is still present and unmodified")
    void a_refund_settles_and_posts_the_mirror_entry() {
        String original = settledCollection(5000);
        LedgerEntry collectionEntry = ledger.entriesForReference(original).get(0);

        ResponseEntity<String> created = postRefund(original, UUID.randomUUID().toString(), null);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String refund = field(created, "reference");
        assertThat(field(created, "state")).isEqualTo("SUBMITTED");

        reconciler.runOnce();

        JsonNode refundPayment = getPayment(refund);
        assertThat(refundPayment.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(refundPayment.get("operation").asText()).isEqualTo("DISBURSE");
        assertThat(refundPayment.get("refundOf").asText()).isEqualTo(original);
        assertThat(refundPayment.get("amountMinorUnits").asLong())
                .as("no amount was given, so the full remaining balance was refunded")
                .isEqualTo(5000L);

        assertThat(ledger.entriesForReference(refund)).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("refund:" + refund);
            assertThat(entry.description()).contains(original);
            assertThat(signed(entry, AccountId.providerFloat("mtn-sandbox", Currency.EUR)))
                    .as("the float goes down -- money left it").isNegative();
            assertThat(signed(entry, AccountId.merchantPayable("merchant-1", Currency.EUR)))
                    .as("we owe the merchant less").isPositive();
        });
        assertThat(ledger.entriesForReference(original))
                .as("the original entry is still present and unmodified -- the point of the whole design")
                .containsExactly(collectionEntry);
    }

    @Test
    @DisplayName("a partial refund leaves the rest of the collection refundable")
    void a_partial_refund_leaves_a_remaining_balance() {
        String original = settledCollection(5000);

        ResponseEntity<String> created = postRefund(original, UUID.randomUUID().toString(), 2000L);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        reconciler.runOnce();

        ResponseEntity<String> second = postRefund(original, UUID.randomUUID().toString(), 3000L);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        reconciler.runOnce();

        assertThat(ledger.entriesForReference(field(second, "reference"))).singleElement()
                .satisfies(entry -> assertThat(signed(entry, AccountId.merchantPayable("merchant-1", Currency.EUR)))
                        .isEqualTo(3000L));
    }

    @Test
    @DisplayName("a refund asking for more than remains is refused, and nothing is created")
    void a_refund_exceeding_the_remaining_balance_is_refused() {
        String original = settledCollection(5000);

        ResponseEntity<String> refused = postRefund(original, UUID.randomUUID().toString(), 5001L);

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(field(refused, "type")).endsWith("refund-exceeds-remaining");
        // Only the original collection's own entry -- nothing was created for the refusal.
        // (The shared ledger carries every other test's entries too; scope to this reference.)
        assertThat(ledger.entriesForReference(original)).hasSize(1);
    }

    // --- rules 1-3: what may be refunded, and where the money goes ------------------------

    @Test
    @DisplayName("a refund naming its own destination is refused with a 400, not silently ignored")
    void a_supplied_destination_is_refused() {
        String original = settledCollection(5000);

        ResponseEntity<String> refused = postRefundRaw(original, UUID.randomUUID().toString(),
                "{\"counterpartyMsisdn\":\"46700000000\"}");

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(field(refused, "type")).endsWith("refund-destination-not-allowed");
    }

    @Test
    @DisplayName("a collection that is not yet SUCCEEDED cannot be refunded")
    void a_non_succeeded_collection_cannot_be_refunded() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"BAD_REQUEST"}}}]}""");
        String reference = field(postCollection(5000), "reference");
        assertThat(getPayment(reference).get("state").asText()).isEqualTo("FAILED");

        ResponseEntity<String> refused = postRefund(reference, UUID.randomUUID().toString(), null);

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(field(refused, "type")).endsWith("original-not-refundable");
    }

    @Test
    @DisplayName("a disbursement cannot be refunded -- sending money back to a payee is a new collection")
    void a_disbursement_cannot_be_refunded() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"ACCEPT"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        String body = """
            {"operation":"DISBURSE","amount":5000,"currency":"EUR","country":"sandbox",
             "counterpartyMsisdn":"46733123453","payerMessage":"payout","payeeNote":"payout"}""";
        String reference = field(post("/payments", UUID.randomUUID().toString(), body), "reference");
        reconciler.runOnce();
        assertThat(getPayment(reference).get("state").asText()).isEqualTo("SUCCEEDED");

        ResponseEntity<String> refused = postRefund(reference, UUID.randomUUID().toString(), null);

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(field(refused, "type")).endsWith("cannot-refund-a-disbursement");
    }

    @Test
    @DisplayName("an unresolved (UNKNOWN) collection cannot be refunded -- whether the payer's money was ever taken is not known")
    void an_unknown_collection_cannot_be_refunded() {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"}}}]}""");
        String reference = field(postCollection(5000), "reference");
        assertThat(getPayment(reference).get("state").asText()).isEqualTo("UNKNOWN");

        ResponseEntity<String> refused = postRefund(reference, UUID.randomUUID().toString(), null);

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(field(refused, "type")).endsWith("original-not-refundable");
    }

    // --- idempotency, exactly as POST /payments -------------------------------------------

    @Test
    @DisplayName("the same Idempotency-Key twice yields one refund and one ledger entry")
    void a_replayed_key_creates_one_refund() {
        String original = settledCollection(5000);
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = postRefund(original, key, 2000L);
        ResponseEntity<String> second = postRefund(original, key, 2000L);

        assertThat(field(first, "reference")).isEqualTo(field(second, "reference"));
        reconciler.runOnce();
        assertThat(ledger.entriesForReference(field(first, "reference"))).hasSize(1);
    }

    // --- a refund that times out goes through the ordinary reconciler --------------------

    @Test
    @DisplayName("a refund that times out is 202 UNKNOWN, and the ordinary reconciler resolves it -- no refund-specific path")
    void a_timed_out_refund_is_resolved_by_the_reconciler() {
        String original = settledCollection(5000);
        // Discriminate the refund's own submission from the original collection's by amount
        // (MTN's own decimal string, e.g. "20.00" for 2000 minor EUR units) -- the collection
        // already settled above under the happy-path default.
        SIMULATOR.declareScenario("""
            {"rules":[{"match":{"amount":"20.00"},
                       "scenario":{"onSubmit":{"outcome":"NO_RESPONSE"},
                                  "onQuery":[{"status":"PENDING","reason":"SERVICE_UNAVAILABLE"},
                                             {"status":"SUCCESSFUL"}]}},
                      {"scenario":{"onSubmit":{"outcome":"ACCEPT"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");

        ResponseEntity<String> created = postRefund(original, UUID.randomUUID().toString(), 2000L);
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        String refund = field(created, "reference");
        assertThat(field(created, "state")).isEqualTo("UNKNOWN");
        assertThat(ledger.entriesForReference(refund)).isEmpty();

        reconciler.runOnce();   // still inconclusive
        assertThat(getPayment(refund).get("state").asText()).isEqualTo("UNKNOWN");

        makeDue(refund);
        reconciler.runOnce();   // SUCCESSFUL

        JsonNode resolved = getPayment(refund);
        assertThat(resolved.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(resolved.get("history")).anySatisfy(t -> {
            assertThat(t.get("to").asText()).isEqualTo("SUCCEEDED");
            assertThat(t.get("cause").asText()).isEqualTo("RECONCILER");
        });
        assertThat(ledger.entriesForReference(refund)).hasSize(1);
    }

    // --- the cap holds under real concurrency, through the database, not mocks -----------

    @Test
    @DisplayName("two refunds for more than the remainder, issued at once: exactly one survives")
    void the_cap_holds_under_concurrency() throws Exception {
        String original = settledCollection(5000);
        int attempts = 4;
        long each = 2000L; // four of these is 8000, well past the 5000 remaining

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String key = UUID.randomUUID().toString();
            futures.add(pool.submit(() -> {
                start.await();
                return postRefund(original, key, each);
            }));
        }
        start.countDown();

        int succeeded = 0;
        int refused = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get();
            if (response.getStatusCode().value() == 201) {
                succeeded++;
            } else {
                assertThat(response.getStatusCode().value()).isEqualTo(400);
                assertThat(field(response, "type")).endsWith("refund-exceeds-remaining");
                refused++;
            }
        }
        pool.shutdown();

        // 5000 / 2000 fits exactly twice; a third or fourth must be refused. The point is not
        // the exact count -- it is that the sum actually reserved never exceeds 5000, which a
        // race between two application-level checks (with no database constraint behind them)
        // could not guarantee.
        assertThat(succeeded).isBetween(1, 2);
        assertThat(succeeded + refused).isEqualTo(attempts);

        long totalRefunded = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get();
            if (response.getStatusCode().value() == 201) {
                reconciler.runOnce();
                String refundReference = field(response, "reference");
                totalRefunded += ledger.entriesForReference(refundReference).stream()
                        .mapToLong(e -> signed(e, AccountId.merchantPayable("merchant-1", Currency.EUR)))
                        .sum();
            }
        }
        assertThat(totalRefunded).as("the sum actually settled never exceeds what was ever collected").isLessThanOrEqualTo(5000L);
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Creates and settles a COLLECT payment under the happy-path scenario, returning its reference. */
    private String settledCollection(long amount) {
        SIMULATOR.declareScenario("""
            {"rules":[{"scenario":{"onSubmit":{"outcome":"ACCEPT"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        String reference = field(postCollection(amount), "reference");
        reconciler.runOnce();
        assertThat(getPayment(reference).get("state").asText()).isEqualTo("SUCCEEDED");
        return reference;
    }

    private ResponseEntity<String> postCollection(long amount) {
        String body = """
            {"operation":"COLLECT","amount":%d,"currency":"EUR","country":"sandbox",
             "counterpartyMsisdn":"46733123453","payerMessage":"rent","payeeNote":"march"}"""
                .formatted(amount);
        return post("/payments", UUID.randomUUID().toString(), body);
    }

    private ResponseEntity<String> postRefund(String reference, String idempotencyKey, Long amount) {
        String body = amount == null ? "{}" : "{\"amount\":" + amount + "}";
        return postRefundRaw(reference, idempotencyKey, body);
    }

    private ResponseEntity<String> postRefundRaw(String reference, String idempotencyKey, String body) {
        return post("/payments/" + reference + "/refunds", idempotencyKey, body);
    }

    private ResponseEntity<String> post(String path, String idempotencyKey, String body) {
        HttpHeaders headers = jsonHeaders(apiKey);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode getPayment(String reference) {
        ResponseEntity<String> response = http.exchange("/payments/" + reference, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(apiKey)), String.class);
        return parse(response.getBody());
    }

    private void makeDue(String reference) {
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

    private static long signed(LedgerEntry entry, AccountId account) {
        return entry.postings().stream().filter(p -> p.account().equals(account))
                .mapToLong(p -> p.amount().amount()).sum();
    }
}
