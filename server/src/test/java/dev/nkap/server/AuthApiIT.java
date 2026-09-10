package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.statement.StatementImport;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Callers are authenticated, and the merchant comes from the credential — never the body.
 *
 * <p>The one that had to be written first: two merchants that pick the same
 * {@code Idempotency-Key} get two payments, because the identity that scopes idempotency is
 * the one the API key established, not a field either of them put in the request. Before this
 * slice the body decided, so the second caller was handed the first's stored response.
 */
class AuthApiIT extends PostgresSpringBootIT {

    private static final EmbeddedSimulator SIMULATOR = EmbeddedSimulator.start();

    @DynamicPropertySource
    static void mtnPointsAtTheSimulator(DynamicPropertyRegistry registry) {
        registry.add("nkap.provider.mtn.base-url", SIMULATOR::baseUri);
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> "test-subscription-key");
        registry.add("nkap.provider.mtn.api-user", () -> "test-api-user");
        registry.add("nkap.provider.mtn.api-key", () -> "test-api-key");
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
    ObjectMapper json;

    @Autowired
    ApiKeyStore apiKeys;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StatementImport statementImport;

    @BeforeEach
    void resetSimulator() {
        SIMULATOR.reset();
    }

    @Test
    @DisplayName("two merchants, the same Idempotency-Key, different keys: two payments, not a replay")
    void two_merchants_same_idempotency_key_get_two_payments() {
        String aliceKey = apiKeys.provision("alice-" + System.nanoTime(), false, "test").token();
        String bobKey = apiKeys.provision("bob-" + System.nanoTime(), false, "test").token();
        String sharedIdempotencyKey = "order-42";

        ResponseEntity<String> aliceResponse = post(aliceKey, sharedIdempotencyKey);
        ResponseEntity<String> bobResponse = post(bobKey, sharedIdempotencyKey);

        assertThat(aliceResponse.getStatusCode().is2xxSuccessful())
                .as("alice's payment was created: %s", aliceResponse.getBody()).isTrue();
        assertThat(bobResponse.getStatusCode().is2xxSuccessful())
                .as("bob's payment was created, not answered by replaying alice's: %s", bobResponse.getBody()).isTrue();

        String aliceReference = reference(aliceResponse);
        String bobReference = reference(bobResponse);
        assertThat(bobReference)
                .as("bob got his own payment; the shared Idempotency-Key did not collide across merchants")
                .isNotEqualTo(aliceReference);
    }

    // === 2. one merchant cannot read another's payment, and the answer is an unknown reference's ===

    @Test
    @DisplayName("a merchant reading another merchant's payment gets the same 404 as for a reference that does not exist")
    void a_cross_merchant_read_is_indistinguishable_from_an_unknown_reference() {
        String aliceKey = apiKeys.provision("alice-" + System.nanoTime(), false, "test").token();
        String bobKey = apiKeys.provision("bob-" + System.nanoTime(), false, "test").token();

        String aliceReference = reference(post(aliceKey, "own-payment"));

        String unknownReference = UUID.randomUUID().toString();
        ResponseEntity<String> bobReadsAlice = get(bobKey, aliceReference);
        ResponseEntity<String> bobReadsNothing = get(bobKey, unknownReference);

        assertThat(bobReadsAlice.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Same status, same problem type, same title — bob cannot tell "exists but not
        // yours" from "does not exist". The reference echoed in `detail`/`instance` is bob's
        // own input, so it reveals nothing; substitute it and the bodies are identical.
        assertThat(bobReadsAlice.getBody().replace(aliceReference, "REF"))
                .as("indistinguishable from a reference that never existed")
                .isEqualTo(bobReadsNothing.getBody().replace(unknownReference, "REF"));

        assertThat(get(aliceKey, aliceReference).getStatusCode())
                .as("alice can still read her own").isEqualTo(HttpStatus.OK);
    }

    // === 3. no credential, and an unknown credential: 401, nothing created ==========

    @Test
    @DisplayName("a missing key and an unknown key are both 401, with the same body, and no payment is created")
    void missing_and_unknown_credentials_are_401_and_create_nothing() {
        HttpHeaders noAuth = json(null);
        noAuth.set("Idempotency-Key", "no-auth");
        ResponseEntity<String> missing = http.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(paymentBody(), noAuth), String.class);

        ResponseEntity<String> unknown = http.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(paymentBody(), authed("nkap_not-a-real-key", "unknown-auth")), String.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getBody())
                .as("the same answer whether the key is absent or simply wrong — no oracle for which keys exist")
                .isEqualTo(missing.getBody());
        assertThat(missing.getHeaders().getLocation()).as("nothing was created").isNull();
    }

    // === 4. the statement report needs an admin key ================================

    @Test
    @DisplayName("a merchant key on the statement report is 403; an admin key is allowed")
    void the_statement_report_requires_an_admin_key() throws Exception {
        Path file = Files.createTempFile("statement-", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
                + "auth-orphan-" + System.nanoTime() + ",1000,0,EUR,2026-09-10T14:00:00Z,SETTLED\n");
        UUID importId = statementImport.run(file, ProviderId.of("mtn"), "auth-test.csv").report().importId();

        String merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "test").token();
        String adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "admin").token();

        assertThat(getImport(merchantKey, importId).getStatusCode())
                .as("operator data is not a merchant's business").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getImport(adminKey, importId).getStatusCode())
                .as("an admin key reads it").isEqualTo(HttpStatus.OK);
    }

    // === 5. the callback endpoint still takes no credential =========================

    @Test
    @DisplayName("the callback endpoint answers with no Authorization header, so nobody 'fixes' it into requiring one")
    void the_callback_endpoint_stays_unauthenticated() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No Authorization. A callback for a reference this gateway never issued is a 202,
        // and it must stay that — the operator sends no credential.
        String body = "{\"referenceId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESSFUL\"}";

        ResponseEntity<String> answer = http.exchange("/callbacks/mtn", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(answer.getStatusCode())
                .as("not 401, not 403 — the callback path is unauthenticated by design")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    // === 6. only a hash is stored, and no path returns a key =======================

    @Test
    @DisplayName("the stored value is the key's SHA-256, not the key; the store has no read-back path")
    void the_stored_form_is_a_hash_not_the_key() throws Exception {
        String merchant = "hash-check-" + System.nanoTime();
        String token = apiKeys.provision(merchant, false, "test").token();

        String stored = jdbc.queryForObject(
                "SELECT token_sha256 FROM api_key WHERE merchant_id = ?", String.class, merchant);

        assertThat(stored).as("not the key itself").isNotEqualTo(token);
        assertThat(stored).as("a 64-hex-char SHA-256").matches("[0-9a-f]{64}");
        assertThat(stored).as("exactly SHA-256(token)").isEqualTo(sha256Hex(token));

        // There is no method on ApiKeyStore that returns a token, and no column holds one:
        // the only stored form is this hash. authenticate() takes a token and gives back an
        // ApiCredential, which has no accessor for one.
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'api_key' ORDER BY column_name", String.class))
                .containsExactly("created_at", "id", "is_admin", "label", "last_used_at", "merchant_id", "token_sha256");
    }

    // === helpers ==================================================================

    private static String paymentBody() {
        return """
                {"operation":"COLLECT","amount":5000,"currency":"EUR",
                 "counterpartyMsisdn":"46733123453","payerMessage":"rent","payeeNote":"march"}""";
    }

    private HttpHeaders json(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    private HttpHeaders authed(String bearer, String idempotencyKey) {
        HttpHeaders headers = json(bearer);
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    private ResponseEntity<String> post(String apiKey, String idempotencyKey) {
        return http.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(paymentBody(), authed(apiKey, idempotencyKey)), String.class);
    }

    private ResponseEntity<String> get(String apiKey, String reference) {
        return http.exchange("/payments/" + reference, HttpMethod.GET, new HttpEntity<>(json(apiKey)), String.class);
    }

    private ResponseEntity<String> getImport(String apiKey, UUID importId) {
        return http.exchange("/statements/imports/" + importId, HttpMethod.GET,
                new HttpEntity<>(json(apiKey)), String.class);
    }

    private static String sha256Hex(String value) throws Exception {
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(sha.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String reference(ResponseEntity<String> response) {
        try {
            JsonNode node = json.readTree(response.getBody());
            assertThat(node.hasNonNull("reference")).as("response carries a reference: %s", response.getBody()).isTrue();
            return node.get("reference").asText();
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + response.getBody(), e);
        }
    }
}
