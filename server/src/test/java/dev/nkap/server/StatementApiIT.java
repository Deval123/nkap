package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.server.support.PostgresSpringBootIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code POST /statements/imports} and {@code GET /statements/imports/{id}} end to end: a
 * real server and a real PostgreSQL. The reconciliation decisions themselves are held by
 * {@code StatementReconciliationIT}; this proves the file gets in, the report comes back,
 * and it can be read again afterwards.
 */
class StatementApiIT extends PostgresSpringBootIT {

    private static final MediaType TEXT_CSV = MediaType.valueOf("text/csv");

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

    @Test
    @DisplayName("an uploaded statement is reconciled, the report is returned, and GET reads the same report back")
    void an_upload_returns_a_report_that_can_be_read_back() throws Exception {
        String orphan = "api-orphan-" + System.nanoTime();
        String csv = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
                + orphan + ",2500,0,EUR,2026-09-10T14:00:00Z,SETTLED\n";

        ResponseEntity<String> posted = http.exchange(
                "/statements/imports?provider=mtn&source=api-test.csv", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(csv, csvHeaders()), String.class);

        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode report = json.readTree(posted.getBody());
        assertThat(report.get("sourceName").asText()).isEqualTo("api-test.csv");
        assertThat(report.get("lineCount").asInt()).isEqualTo(1);
        assertThat(report.get("findings")).anySatisfy(f ->
                assertThat(f.get("kind").asText()).isEqualTo("SUSPENSE_POSTED"));
        String importId = report.get("importId").asText();

        ResponseEntity<String> fetched = http.getForEntity("/statements/imports/" + importId, String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode reloaded = json.readTree(fetched.getBody());
        assertThat(reloaded.get("importId").asText()).isEqualTo(importId);
        assertThat(reloaded.get("findings")).anySatisfy(f -> {
            assertThat(f.get("kind").asText()).isEqualTo("SUSPENSE_POSTED");
            assertThat(f.get("operatorTransactionId").asText()).isEqualTo(orphan);
        });
    }

    @Test
    @DisplayName("a file that will not parse is 400 problem+json, not a 500 and not a silent empty report")
    void a_malformed_file_is_a_400() throws Exception {
        ResponseEntity<String> posted = http.exchange(
                "/statements/imports?provider=mtn", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("not,a,valid,header\n", csvHeaders()), String.class);

        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = json.readTree(posted.getBody());
        assertThat(problem.get("type").asText()).endsWith("malformed-statement");
    }

    @Test
    @DisplayName("GET on an unknown import id is 404 problem+json")
    void an_unknown_import_is_a_404() throws Exception {
        ResponseEntity<String> fetched = http.getForEntity(
                "/statements/imports/" + java.util.UUID.randomUUID(), String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json.readTree(fetched.getBody()).get("type").asText()).endsWith("statement-import-not-found");
    }

    private static HttpHeaders csvHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(TEXT_CSV);
        return headers;
    }
}
