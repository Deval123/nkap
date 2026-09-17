package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.SubmitResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * MANUAL. Talks to the real MTN sandbox, so it is disabled unless {@code NKAP_MTN_SANDBOX}
 * is set, and it never runs in CI — CI has no credentials, and a test that depends on
 * someone else's uptime fails for reasons that are not about the code.
 *
 * <p>Run it by hand:
 *
 * <pre>
 *   # ~/.nkap/mtn-sandbox.env  (KEY=VALUE per line)
 *   BASE_URL=https://sandbox.momodeveloper.mtn.com
 *   TARGET_ENVIRONMENT=sandbox
 *   SUBSCRIPTION_KEY=...
 *   API_USER=...
 *   API_KEY=...
 *   CURRENCY=EUR
 *   COUNTRY=sandbox
 *   MSISDN=56733123453
 *
 *   NKAP_MTN_SANDBOX=1 mvn -pl provider-mtn test -Dtest=MtnSandboxIT
 * </pre>
 *
 * <p>{@code MSISDN} defaults to {@code 56733123453}, MTN's published <em>Success</em> test
 * number — settled quickly enough for a manual test to poll for it, observed once. This
 * repository's own {@code 46733123453} fixture, which this test defaulted to before issue
 * #117, never concludes through the status endpoint at all: its terminal state only arrives
 * by callback, on a path no deployment of this gateway can currently receive (#116). Override
 * the default only to exercise a different published outcome.
 */
@EnabledIfEnvironmentVariable(named = "NKAP_MTN_SANDBOX", matches = ".+")
class MtnSandboxIT {

    @Test
    @DisplayName("MANUAL: submit a small payment to the real sandbox and read its status back")
    void submit_and_query_against_the_real_sandbox() throws Exception {
        Map<String, String> env = readEnv(
                Path.of(System.getProperty("user.home"), ".nkap", "mtn-sandbox.env"));

        MtnProfile profile = new MtnProfile(
                URI.create(env.get("BASE_URL")),
                env.getOrDefault("TARGET_ENVIRONMENT", "sandbox"),
                env.get("SUBSCRIPTION_KEY"),
                env.get("API_USER"),
                env.get("API_KEY"),
                Currency.valueOf(env.getOrDefault("CURRENCY", "EUR")),
                env.getOrDefault("COUNTRY", "sandbox"));

        MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profile);
        ReferenceId reference = ReferenceId.newReference();
        PaymentIntent intent = new PaymentIntent(Capability.Operation.COLLECT,
                Money.of(100, profile.currency()),
                env.getOrDefault("MSISDN", "56733123453"),
                "nkap manual sandbox test", "nkap manual sandbox test", Map.of());

        SubmitResult submitted = adapter.submit(intent, reference);
        System.out.println("submit  " + reference + " -> " + submitted);

        // The only observation behind this default settling is a query made ten seconds after
        // submission, in one run, by a script that slept first — nothing has observed what it
        // answers at t+0, and it may well still be PENDING that soon. Poll for a bounded window
        // rather than asserting on the first query: giving a real operator time to reach a
        // terminal state tests the adapter, not MTN's latency, and a single immediate query
        // would be asserting a timing property nobody has actually observed.
        ProviderStatus status = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (attempt > 0) {
                Thread.sleep(4000);
            }
            status = adapter.query(QuerySubject.of(reference), Capability.Operation.COLLECT);
            System.out.println("query   " + reference + " -> " + status.state()
                    + " (" + status.providerStatusCode() + ")");
            if (status.state() != PaymentState.PENDING) {
                break;
            }
        }

        assertThat(status.state()).as("query result").isEqualTo(PaymentState.SUCCEEDED);
        assertThat(status.transactionId())
                .as("MTN's financialTransactionId for a SUCCESSFUL payment")
                .isPresent();
    }

    private static Map<String, String> readEnv(Path file) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                values.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
            }
        }
        return values;
    }
}
