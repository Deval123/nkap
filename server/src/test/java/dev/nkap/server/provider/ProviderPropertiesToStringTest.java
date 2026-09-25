package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.money.Currency;
import dev.nkap.server.support.RecordToString;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider {@code @ConfigurationProperties} records mask their credentials in
 * {@code toString()}. A bound object can reach an error message, a log line or a failing
 * assertion by paths this code does not control, so every credential component is masked, and
 * every component is accounted for.
 */
class ProviderPropertiesToStringTest {

    // --- MpesaInstallation ---------------------------------------------------------------------------

    private static final Map<String, String> MPESA_INSTALLATION_SECRETS = Map.of(
            "passkey", "canary-passkey-bfb279f9aa9bdbcf",
            "consumerKey", "canary-consumer-key-7Qx2",
            "consumerSecret", "canary-consumer-secret-Lm9p");

    private static MpesaProperties.Installation mpesaInstallation() {
        Map<String, String> s = MPESA_INSTALLATION_SECRETS;
        return new MpesaProperties.Installation(URI.create("https://sandbox.safaricom.co.ke"), "174379",
                s.get("passkey"), s.get("consumerKey"), s.get("consumerSecret"), Currency.KES, "ke",
                Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("MpesaInstallation.toString() prints none of its credentials, so a bound installation reaching an error message or a log cannot leak one")
    void mpesaInstallation_prints_no_credential() {
        String text = mpesaInstallation().toString();
        MPESA_INSTALLATION_SECRETS.forEach((field, secret) -> assertThat(text).as(field).doesNotContain(secret));
    }

    @Test
    @DisplayName("MpesaInstallation.toString() still prints the base URL, the shortcode and the country")
    void mpesaInstallation_still_prints_what_is_not_secret() {
        assertThat(mpesaInstallation().toString()).contains("baseUrl=https://sandbox.safaricom.co.ke")
                .contains("businessShortCode=174379")
                .contains("country=ke");
    }

    @Test
    @DisplayName("every MpesaInstallation component is accounted for in toString(): printed in clear or masked by a constant")
    void every_mpesaInstallation_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(mpesaInstallation(), Set.of("baseUrl", "businessShortCode", "currency", "country", "requestTimeout"),
                MPESA_INSTALLATION_SECRETS.keySet(), MpesaProperties.MASKED);
    }

    // --- MtnInstallation ---------------------------------------------------------------------------

    private static final Map<String, String> MTN_INSTALLATION_SECRETS = Map.of(
            "subscriptionKey", "canary-subscription-key-3e8d",
            "apiUser", "canary-api-user-0c41",
            "apiKey", "canary-api-key-9a7f");

    private static MtnProperties.Installation mtnInstallation() {
        Map<String, String> s = MTN_INSTALLATION_SECRETS;
        return new MtnProperties.Installation(URI.create("https://sandbox.momodeveloper.mtn.com"), "sandbox",
                s.get("subscriptionKey"), s.get("apiUser"), s.get("apiKey"), Currency.XAF, "cm",
                Duration.ofSeconds(20), disbursement());
    }

    @Test
    @DisplayName("MtnInstallation.toString() prints none of its credentials, so a bound installation reaching an error message or a log cannot leak one")
    void mtnInstallation_prints_no_credential() {
        String text = mtnInstallation().toString();
        MTN_INSTALLATION_SECRETS.forEach((field, secret) -> assertThat(text).as(field).doesNotContain(secret));
    }

    @Test
    @DisplayName("MtnInstallation.toString() still prints the base URL and the country, and its nested Disbursement stays masked")
    void mtnInstallation_still_prints_what_is_not_secret() {
        assertThat(mtnInstallation().toString()).contains("baseUrl=https://sandbox.momodeveloper.mtn.com")
                .contains("country=cm")
                .doesNotContain(DISBURSEMENT_SECRETS.get("apiKey"));
    }

    @Test
    @DisplayName("every MtnInstallation component is accounted for in toString(): printed in clear or masked by a constant")
    void every_mtnInstallation_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(mtnInstallation(), Set.of("baseUrl", "targetEnvironment", "currency", "country", "requestTimeout", "disbursement"),
                MTN_INSTALLATION_SECRETS.keySet(), MtnProperties.MASKED);
    }

    // --- Disbursement ---------------------------------------------------------------------------

    private static final Map<String, String> DISBURSEMENT_SECRETS = Map.of(
            "subscriptionKey", "canary-disbursement-subscription-key-6f3c",
            "apiUser", "canary-disbursement-api-user-2d90",
            "apiKey", "canary-disbursement-api-key-e71b");

    private static MtnProperties.Disbursement disbursement() {
        Map<String, String> s = DISBURSEMENT_SECRETS;
        return new MtnProperties.Disbursement(s.get("subscriptionKey"), s.get("apiUser"), s.get("apiKey"));
    }

    @Test
    @DisplayName("Disbursement.toString() prints none of its credentials, printed on its own or nested inside an installation")
    void disbursement_prints_no_credential() {
        String text = disbursement().toString();
        DISBURSEMENT_SECRETS.forEach((field, secret) -> assertThat(text).as(field).doesNotContain(secret));
    }

    @Test
    @DisplayName("Disbursement.toString() prints its component names and nothing else: every component is a credential")
    void disbursement_still_prints_what_is_not_secret() {
        assertThat(disbursement().toString()).isEqualTo("Disbursement[subscriptionKey=***, apiUser=***, apiKey=***]");
    }

    @Test
    @DisplayName("every Disbursement component is accounted for in toString(): printed in clear or masked by a constant")
    void every_disbursement_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(disbursement(), Set.of(),
                DISBURSEMENT_SECRETS.keySet(), MtnProperties.MASKED);
    }
}
