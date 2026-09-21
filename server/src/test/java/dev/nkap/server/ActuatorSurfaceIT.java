package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.management.EscalatedPaymentsEndpoint;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.support.PostgresSpringBootIT;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * What {@code /actuator} exposes, and where. Two claims, not one, since the follow-up plan
 * for issue #75 turned this into two: the <strong>management</strong> port ({@code
 * management.server.port}, {@code application.yml}) serves exactly {@code health} and
 * {@code prometheus} — the same property this class asserted before, only relocated — and
 * the <strong>API</strong> port (published, the one the world can reach) serves neither. The
 * two are asserted separately, deliberately: a passing "the management port is right" test
 * would once have been the whole story, and the defect this file now also guards against is
 * exactly that gap — the API port answering {@code /actuator/**} because nobody thought to
 * check it didn't.
 *
 * <p>{@code AuthWebConfig} <strong>still</strong> excludes {@code /actuator/**} from
 * authentication, and this class is why that exclusion turned out to still be needed: the
 * management port's embedded server runs in a Spring context that is a <em>child</em> of
 * this one, and a child context inherits its parent's {@code WebMvcConfigurer} beans —
 * without the exclusion, health and metrics would both demand an API key on a port no
 * scraper or health check ever sends one to. That surprised the first draft of this port
 * separation, which assumed the exclusion had become dead code; it had not, only what it
 * protects had moved.
 */
class ActuatorSurfaceIT extends PostgresSpringBootIT {

    @DynamicPropertySource
    static void mtnConfigThisTestNeverCalls(DynamicPropertyRegistry registry) {
        // This test never reaches MTN; any non-null config satisfies the context (same
        // pattern as StatementApiIT).
        registry.add("nkap.provider.mtn.installations[0].base-url", () -> "http://localhost:1");
        registry.add("nkap.provider.mtn.installations[0].target-environment", () -> "sandbox");
        registry.add("nkap.provider.mtn.installations[0].subscription-key", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].api-user", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].api-key", () -> "unused");
        registry.add("nkap.provider.mtn.installations[0].currency", () -> "EUR");
        registry.add("nkap.provider.mtn.installations[0].country", () -> "sandbox");
        registry.add("nkap.provider.default", () -> "mtn-sandbox");
    }

    /** The API port — random per test run, resolved by TestRestTemplate the same way every other *ApiIT uses it. */
    @Autowired
    TestRestTemplate api;

    @Autowired
    ObjectMapper json;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * {@code webEnvironment = RANDOM_PORT} randomises {@code management.server.port} to an
     * ephemeral value the same way it randomises {@code server.port} — application.yml's
     * fixed 9464 is not what actually gets bound in a test. Spring exposes the real one as
     * this property, the management-port counterpart of {@code local.server.port}
     * ({@code @LocalServerPort} wraps that one; there is no {@code @LocalManagementPort}, so
     * this reads the property directly).
     */
    @Value("${local.management.port}")
    private int managementPort;

    /**
     * A plain {@link RestTemplate}, not {@link TestRestTemplate}: the latter is wired to the
     * API port ({@code @LocalServerPort}) and there is no management-port equivalent to
     * autowire. Given a no-op error handler so a 4xx becomes an ordinary
     * {@link ResponseEntity} rather than a thrown exception — {@code TestRestTemplate}
     * behaves the same way by default, and a 404 is exactly what several of these tests
     * assert on.
     */
    private final RestTemplate management = nonThrowingRestTemplate();

    private static RestTemplate nonThrowingRestTemplate() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    // --- the API port: nothing here, not even gated -----------------------------------

    @Test
    @DisplayName("the API port does not serve /actuator/prometheus — not gated, not there")
    void the_api_port_does_not_serve_prometheus() {
        ResponseEntity<String> response = api.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the API port does not serve /actuator/health either — health moved with the rest")
    void the_api_port_does_not_serve_health() {
        ResponseEntity<String> response = api.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the API port does not serve /actuator/escalatedPayments — the property the whole design rests on (issue #113)")
    void the_api_port_does_not_serve_escalated_payments() {
        ResponseEntity<String> response = api.getForEntity("/actuator/escalatedPayments", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- the management port: exactly health and prometheus, unauthenticated ----------

    @Test
    @DisplayName("the management port's actuator index lists exactly health, prometheus and escalatedPayments — nothing added by accident")
    void the_index_lists_exactly_the_three_intended_endpoints() throws Exception {
        ResponseEntity<String> response = onManagementPort("/actuator", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode links = json.readTree(response.getBody()).get("_links");
        List<String> exposed = new ArrayList<>();
        links.fieldNames().forEachRemaining(exposed::add);

        assertThat(exposed).as("self is the index link, not an endpoint")
                .containsExactlyInAnyOrder("self", "health", "health-path", "prometheus", "escalatedPayments");
    }

    @Test
    @DisplayName("GET /actuator/health on the management port needs no key — the compose file's health check carries none")
    void health_needs_no_key() {
        ResponseEntity<String> response = onManagementPort("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /actuator/prometheus on the management port needs no key — a Prometheus scrape carries none either")
    void prometheus_needs_no_key() {
        ResponseEntity<String> response = onManagementPort("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("nkap_suspense_balance");
    }

    @Test
    @DisplayName("every other actuator endpoint is absent on the management port too — not merely gated, not there at all")
    void every_other_actuator_endpoint_is_absent() {
        for (String path : List.of("env", "beans", "configprops", "mappings", "heapdump", "threaddump", "loggers")) {
            ResponseEntity<String> response = onManagementPort("/actuator/" + path, String.class);
            assertThat(response.getStatusCode())
                    .as("/actuator/%s must not be exposed", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // --- GET /actuator/escalatedPayments (issue #113) ---------------------------------

    @Test
    @DisplayName("escalatedPayments returns exactly the escalated payments, oldest first, with the operational fields and none of the excluded ones")
    void escalated_payments_returns_exactly_the_escalated_ones_oldest_first() throws Exception {
        Instant t0 = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        ReferenceId older = anEscalatedPayment("merchant-escalated-1", t0, 5);
        ReferenceId newer = anEscalatedPayment("merchant-escalated-2", t0.plus(Duration.ofMinutes(10)), 2);
        ReferenceId untouched = anUnresolvedButNotEscalatedPayment("merchant-untouched");

        JsonNode items = json.readTree(onManagementPort("/actuator/escalatedPayments", String.class).getBody()).get("payments");

        List<String> all = new ArrayList<>();
        items.forEach(item -> all.add(item.get("reference").asText()));
        assertThat(all)
                .as("an unresolved-but-not-escalated payment must never appear")
                .doesNotContain(untouched.toString());

        // Scoped to this test's own fixtures, not the endpoint's entire output: this suite
        // shares one PostgreSQL instance across every *IT class, and RefundApiIT and
        // DisbursementApiIT run a real, long-lived scheduled reconciler that can escalate
        // payments belonging to other, unrelated tests over the course of a full run.
        // Asserting the complete result set here would be asserting facts about tests this
        // one does not control; asserting that both of this test's own payments are present
        // and correctly ordered relative to each other is the property this test can
        // actually own.
        List<String> mine = all.stream().filter(ref -> ref.equals(older.toString()) || ref.equals(newer.toString())).toList();
        assertThat(mine).as("both of this test's escalated payments are present, oldest escalation first")
                .containsExactly(older.toString(), newer.toString());

        JsonNode olderItem = itemFor(items, older);
        assertThat(olderItem.get("provider").asText()).isEqualTo("mtn-sandbox");
        assertThat(olderItem.get("merchantId").asText()).isEqualTo("merchant-escalated-1");
        assertThat(olderItem.get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(olderItem.get("escalatedAt").asText()).isNotEmpty();
        assertThat(olderItem.get("unresolvedSince").asText()).isNotEmpty();
        assertThat(olderItem.get("reconcileAttempts").asInt()).isEqualTo(5);
        assertThat(olderItem.get("reason").asText())
                .as("mtn-sandbox declares QUERY, so this fixture can only have been escalated for exhausting its window (ADR 0014 decision 3, issue #188)")
                .isEqualTo("window_exhausted");

        for (String excluded : List.of("counterpartyMsisdn", "amountMinorUnits", "currency", "payerMessage", "payeeNote")) {
            assertThat(olderItem.has(excluded)).as("%s is customer PII or business volume, not operational triage data", excluded).isFalse();
        }
    }

    private JsonNode itemFor(JsonNode items, ReferenceId reference) {
        for (JsonNode item : items) {
            if (item.get("reference").asText().equals(reference.toString())) {
                return item;
            }
        }
        throw new AssertionError(reference + " is not in the response: " + items);
    }

    @Test
    @DisplayName("escalatedPayments truncates at its documented cap and says so, rather than growing without bound")
    void escalated_payments_truncates_at_the_cap() throws Exception {
        Instant t0 = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < EscalatedPaymentsEndpoint.LIMIT + 1; i++) {
            anEscalatedPayment("merchant-cap-" + i, t0.plus(Duration.ofSeconds(i)), 1);
        }

        JsonNode response = json.readTree(onManagementPort("/actuator/escalatedPayments", String.class).getBody());

        assertThat(response.get("truncated").asBoolean()).as("one more escalated payment than the cap exists").isTrue();
        assertThat(response.get("payments")).hasSize(EscalatedPaymentsEndpoint.LIMIT);
    }

    private ReferenceId anEscalatedPayment(String merchantId, Instant escalatedAt, int reconcileAttempts) {
        ReferenceId reference = anUnresolvedButNotEscalatedPayment(merchantId);
        jdbc.update("UPDATE payment SET escalated_at = ?, unresolved_since = ?, reconcile_attempts = ? WHERE reference = ?",
                OffsetDateTime.ofInstant(escalatedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(escalatedAt, ZoneOffset.UTC),
                reconcileAttempts, reference.value());
        return reference;
    }

    private ReferenceId anUnresolvedButNotEscalatedPayment(String merchantId) {
        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, ProviderId.of("mtn-sandbox"), merchantId,
                new PaymentIntent(Capability.Operation.COLLECT, Money.of(5_000, Currency.EUR),
                        "46733123453", "rent", "march", Map.of()));
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payment.applyTransition(PaymentState.UNKNOWN, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "no answer", "");
        payments.save(payment);
        return reference;
    }

    private <T> ResponseEntity<T> onManagementPort(String path, Class<T> type) {
        return management.getForEntity("http://localhost:" + managementPort + path, type);
    }
}
