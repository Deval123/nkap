package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.auth.ApiKeyStore;
import dev.nkap.server.statement.ReconciliationReport;
import dev.nkap.server.statement.StatementImport;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The HTTP surface of statement reconciliation is the <strong>read side only</strong>, and
 * only for an admin key: {@code GET /statements/imports/{id}} returns a stored report, and
 * there is no {@code POST}. Running an import writes to an append-only ledger from a file
 * with nothing to confirm it, so the import is a command
 * ({@code --nkap.statement.import=<path>}), not a route. This test holds all of that: the
 * admin read works, a merchant key is refused, and the write route is absent.
 */
class StatementApiIT extends PostgresSpringBootIT {

    @DynamicPropertySource
    static void mtnConfigThisTestNeverCalls(DynamicPropertyRegistry registry) {
        // The full context builds an MTN adapter; this test never reaches it, so any
        // non-null config does. (CallbackApiIT points these at an embedded simulator.)
        registry.add("nkap.provider.mtn.base-url", () -> "http://localhost:1");
        registry.add("nkap.provider.mtn.target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.subscription-key", () -> "unused");
        registry.add("nkap.provider.mtn.api-user", () -> "unused");
        registry.add("nkap.provider.mtn.api-key", () -> "unused");
        registry.add("nkap.provider.mtn.currency", () -> "EUR");
        registry.add("nkap.provider.mtn.country", () -> "sandbox");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    StatementImport statementImport;

    @Autowired
    ApiKeyStore apiKeys;

    private String adminKey;

    @BeforeEach
    void provisionAdminKey() {
        adminKey = apiKeys.provision("ops-" + System.nanoTime(), true, "StatementApiIT").token();
    }

    @Test
    @DisplayName("there is no POST /statements/imports — an import is a command, not a write route (even with a valid key)")
    void the_import_route_does_not_exist() {
        HttpHeaders csv = new HttpHeaders();
        csv.setContentType(MediaType.valueOf("text/csv"));
        csv.setBearerAuth(adminKey);
        String body = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
                + "x,1000,0,EUR,2026-09-10T14:00:00Z,SETTLED\n";

        ResponseEntity<String> collection = http.exchange(
                "/statements/imports", HttpMethod.POST, new HttpEntity<>(body, csv), String.class);
        ResponseEntity<String> item = http.exchange(
                "/statements/imports/" + UUID.randomUUID(), HttpMethod.POST, new HttpEntity<>(body, csv), String.class);

        assertThat(collection.getStatusCode())
                .as("POST to the collection path has no handler — not a 2xx, and not a 401 hiding a live route")
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(item.getStatusCode())
                .as("the item path is GET-only")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("GET /statements/imports/{id} with an admin key returns a report stored by a command run")
    void a_stored_report_is_readable_by_an_admin_key() throws Exception {
        String orphan = "api-read-" + System.nanoTime();
        Path file = Files.createTempFile("statement-", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
                + orphan + ",2500,0,EUR,2026-09-10T14:00:00Z,SETTLED\n");

        ReconciliationReport produced = statementImport.run(file, ProviderId.of("mtn"), "api-read-test.csv").report();

        ResponseEntity<String> fetched = getImport(produced.importId().toString(), adminKey);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode reloaded = json.readTree(fetched.getBody());
        assertThat(reloaded.get("importId").asText()).isEqualTo(produced.importId().toString());
        assertThat(reloaded.get("sourceName").asText()).isEqualTo("api-read-test.csv");
        assertThat(reloaded.get("findings")).anySatisfy(f -> {
            assertThat(f.get("kind").asText()).isEqualTo("SUSPENSE_POSTED");
            assertThat(f.get("operatorTransactionId").asText()).isEqualTo(orphan);
        });
    }

    @Test
    @DisplayName("a merchant key on the statement report is 403; no key is 401")
    void the_report_is_gated() {
        String merchantKey = apiKeys.provision("merchant-" + System.nanoTime(), false, "StatementApiIT").token();

        assertThat(getImport(UUID.randomUUID().toString(), merchantKey).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getImport(UUID.randomUUID().toString(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET on an unknown import id, with an admin key, is 404 problem+json")
    void an_unknown_import_is_a_404() throws Exception {
        ResponseEntity<String> fetched = getImport(UUID.randomUUID().toString(), adminKey);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json.readTree(fetched.getBody()).get("type").asText()).endsWith("statement-import-not-found");
    }

    private ResponseEntity<String> getImport(String id, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return http.exchange("/statements/imports/" + id, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
