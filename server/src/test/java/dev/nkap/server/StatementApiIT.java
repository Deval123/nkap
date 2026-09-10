package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.statement.ReconciliationReport;
import dev.nkap.server.statement.StatementImport;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The HTTP surface of statement reconciliation is the <strong>read side only</strong>:
 * {@code GET /statements/imports/{id}} returns a stored report, and there is no {@code POST}.
 * Running an import writes to an append-only ledger from a file with nothing to confirm it,
 * and this application authenticates nothing, so the import is a command
 * ({@code --nkap.statement.import=<path>}), not a route. This test holds both halves of that:
 * the read works, and the write route is absent.
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

    @Test
    @DisplayName("there is no POST /statements/imports — an import is a command, not an anonymous write route")
    void the_import_route_does_not_exist() {
        HttpHeaders csv = new HttpHeaders();
        csv.setContentType(MediaType.valueOf("text/csv"));
        String body = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
                + "x,1000,0,EUR,2026-09-10T14:00:00Z,SETTLED\n";

        ResponseEntity<String> collection = http.exchange(
                "/statements/imports", HttpMethod.POST, new HttpEntity<>(body, csv), String.class);
        ResponseEntity<String> item = http.exchange(
                "/statements/imports/" + java.util.UUID.randomUUID(), HttpMethod.POST, new HttpEntity<>(body, csv), String.class);

        assertThat(collection.getStatusCode())
                .as("POST to the collection path has no handler")
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(item.getStatusCode())
                .as("the item path is GET-only")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("GET /statements/imports/{id} returns a report stored by a command run")
    void a_stored_report_is_readable_over_http() throws Exception {
        String orphan = "api-read-" + System.nanoTime();
        Path file = Files.createTempFile("statement-", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
                + orphan + ",2500,0,EUR,2026-09-10T14:00:00Z,SETTLED\n");

        ReconciliationReport produced = statementImport.run(file, ProviderId.of("mtn"), "api-read-test.csv").report();

        ResponseEntity<String> fetched = http.getForEntity("/statements/imports/" + produced.importId(), String.class);
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
    @DisplayName("GET on an unknown import id is 404 problem+json")
    void an_unknown_import_is_a_404() throws Exception {
        ResponseEntity<String> fetched = http.getForEntity(
                "/statements/imports/" + java.util.UUID.randomUUID(), String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json.readTree(fetched.getBody()).get("type").asText()).endsWith("statement-import-not-found");
    }
}
