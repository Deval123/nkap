package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The declared slots are written twice: once as the placeholders in {@code application.yml} that
 * actually read the environment, and once as {@link DeclaredProviderSlots#BY_OPERATOR}, which the
 * startup check compares the environment against. The second copy exists only because an
 * undeclared country leaves nothing behind to derive it from. This test is what makes the copy
 * acceptable: it reads the real file and fails the build the moment the two disagree, so
 * {@code application.yml} stays the one source of truth.
 */
class DeclaredProviderSlotsTest {

    /** A slot placeholder: {@code ${NKAP_PROVIDER_<OPERATOR>_<COUNTRY>_<FIELD>:...}}. */
    private static final Pattern SLOT_PLACEHOLDER =
            Pattern.compile("\\$\\{NKAP_PROVIDER_([A-Z0-9]+)_([A-Z]{2})_[A-Z0-9_]+[:}]");

    @Test
    @DisplayName("the declared-slot constant names exactly the slots application.yml reads, no more and no fewer")
    void the_constant_matches_application_yml() throws IOException {
        String yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml on the classpath").isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Set<String>> inYaml = new TreeMap<>();
        Matcher placeholder = SLOT_PLACEHOLDER.matcher(yaml);
        while (placeholder.find()) {
            inYaml.computeIfAbsent(placeholder.group(1), operator -> new TreeSet<>()).add(placeholder.group(2));
        }
        assertThat(inYaml).as("slots read by application.yml").isNotEmpty();

        Map<String, Set<String>> declared = new TreeMap<>();
        DeclaredProviderSlots.BY_OPERATOR.forEach((operator, countries) -> declared.put(operator, new TreeSet<>(countries)));

        assertThat(declared).isEqualTo(inYaml);
    }

    // --- what fails ------------------------------------------------------------------------

    @Test
    @DisplayName("a variable for a country a known operator has no slot for fails startup, naming the operator, the country and the declared ones")
    void an_undeclared_country_fails() {
        assertThatThrownBy(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of(
                "NKAP_PROVIDER_MTN_CI_COUNTRY", "NKAP_PROVIDER_MTN_CI_API_KEY")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MTN CI")
                .hasMessageContaining("NKAP_PROVIDER_MTN_CI_*")
                .hasMessageContaining("[CM, GH]")
                .hasMessageContaining("never used");
    }

    @Test
    @DisplayName("a variable for an operator with no adapter fails startup, saying no such adapter is built into this image")
    void an_unknown_operator_fails() {
        assertThatThrownBy(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of("NKAP_PROVIDER_ORANGE_CM_API_KEY")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORANGE CM")
                .hasMessageContaining("no ORANGE adapter is built into this image");
    }

    @Test
    @DisplayName("every offender is reported in one message, sorted, so three stray countries cost one restart")
    void every_offender_is_reported_at_once() {
        assertThatThrownBy(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of(
                "NKAP_PROVIDER_MTN_SN_COUNTRY", "NKAP_PROVIDER_ORANGE_CM_API_KEY",
                "NKAP_PROVIDER_MTN_CI_COUNTRY", "NKAP_PROVIDER_MTN_CI_API_USER", "NKAP_PROVIDER_MPESA_TZ_PASSKEY")))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    String message = e.getMessage();
                    assertThat(message).containsSubsequence("MPESA TZ", "MTN CI", "MTN SN", "ORANGE CM");
                    assertThat(message.split("MTN CI", -1)).as("one line per pair, not per variable").hasSize(2);
                });
    }

    @Test
    @DisplayName("a declared slot spelled in lower or mixed case fails too: nothing reads that spelling")
    void a_lower_case_spelling_fails_even_for_a_declared_slot() {
        assertThatThrownBy(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of("nkap_provider_mtn_cm_country")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MTN CM")
                .hasMessageContaining("lower or mixed case");
        assertThatThrownBy(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of("Nkap_Provider_Mtn_Ci_Country")))
                .hasMessageContaining("MTN CI");
    }

    // --- what must not fail ----------------------------------------------------------------

    @Test
    @DisplayName("NKAP_PROVIDER_DEFAULT alone is not a slot variable and fails nothing")
    void the_default_provider_is_not_a_slot() {
        assertThatCode(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of("NKAP_PROVIDER_DEFAULT")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a declared installation's disbursement block fails nothing")
    void a_disbursement_block_is_not_an_offender() {
        assertThatCode(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of(
                "NKAP_PROVIDER_MTN_CM_DISBURSEMENT_API_KEY",
                "NKAP_PROVIDER_MTN_CM_DISBURSEMENT_API_USER",
                "NKAP_PROVIDER_MTN_CM_DISBURSEMENT_SUBSCRIPTION_KEY")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Spring's relaxed indexed spelling is not a slot variable and is not this check's business")
    void a_relaxed_indexed_spelling_is_not_a_slot() {
        assertThatCode(() -> DeclaredProviderSlots.requireOnlyDeclared(List.of(
                "NKAP_PROVIDER_MTN_INSTALLATIONS_0_COUNTRY", "NKAP_PROVIDER_MPESA_INSTALLATIONS_0_PASSKEY")))
                .doesNotThrowAnyException();
    }

    /**
     * Every variable name {@code charts/nkap} renders for its two CI values files, captured with
     * {@code helm template ... -f charts/nkap/ci/values-test.yaml} and {@code values-mpesa-test.yaml}.
     * A snapshot: a chart change adding a variable is not tied to this list by anything but review.
     */
    @Test
    @DisplayName("every variable the chart renders for its valid test values starts the gateway")
    void what_the_chart_renders_for_a_valid_configuration_fails_nothing() {
        List<String> mtn = List.of("NKAP_DB_PASSWORD", "NKAP_DB_URL", "NKAP_DB_USER", "NKAP_PROVIDER_DEFAULT",
                "NKAP_PROVIDER_MTN_CM_API_KEY", "NKAP_PROVIDER_MTN_CM_API_USER", "NKAP_PROVIDER_MTN_CM_BASE_URL",
                "NKAP_PROVIDER_MTN_CM_COUNTRY", "NKAP_PROVIDER_MTN_CM_CURRENCY",
                "NKAP_PROVIDER_MTN_CM_DISBURSEMENT_API_KEY", "NKAP_PROVIDER_MTN_CM_DISBURSEMENT_API_USER",
                "NKAP_PROVIDER_MTN_CM_DISBURSEMENT_SUBSCRIPTION_KEY", "NKAP_PROVIDER_MTN_CM_REQUEST_TIMEOUT",
                "NKAP_PROVIDER_MTN_CM_SUBSCRIPTION_KEY", "NKAP_PROVIDER_MTN_CM_TARGET_ENVIRONMENT");
        List<String> mpesa = List.of("NKAP_DB_PASSWORD", "NKAP_DB_URL", "NKAP_DB_USER",
                "NKAP_PROVIDER_MPESA_KE_BASE_URL", "NKAP_PROVIDER_MPESA_KE_BUSINESS_SHORT_CODE",
                "NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY", "NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET",
                "NKAP_PROVIDER_MPESA_KE_COUNTRY", "NKAP_PROVIDER_MPESA_KE_CURRENCY", "NKAP_PROVIDER_MPESA_KE_PASSKEY",
                "NKAP_PROVIDER_MPESA_KE_REQUEST_TIMEOUT", "NKAP_PUBLIC_BASE_URL");
        assertThatCode(() -> DeclaredProviderSlots.requireOnlyDeclared(mtn)).doesNotThrowAnyException();
        assertThatCode(() -> DeclaredProviderSlots.requireOnlyDeclared(mpesa)).doesNotThrowAnyException();
    }
}
