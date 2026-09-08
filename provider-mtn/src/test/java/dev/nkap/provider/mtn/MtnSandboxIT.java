package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderStatus;
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
 *   MSISDN=46733123453
 *
 *   NKAP_MTN_SANDBOX=1 mvn -pl provider-mtn test -Dtest=MtnSandboxIT
 * </pre>
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
        PaymentIntent intent = new PaymentIntent(Capability.COLLECT,
                Money.of(100, profile.currency()),
                env.getOrDefault("MSISDN", "46733123453"),
                "nkap manual sandbox test", "nkap manual sandbox test", Map.of());

        SubmitResult submitted = adapter.submit(intent, reference);
        System.out.println("submit  " + reference + " -> " + submitted);

        ProviderStatus status = adapter.query(reference);
        System.out.println("query   " + reference + " -> " + status.state()
                + " (" + status.providerStatusCode() + ")");

        assertThat(status.state()).isIn(
                PaymentState.PENDING, PaymentState.SUCCEEDED, PaymentState.UNKNOWN);
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
