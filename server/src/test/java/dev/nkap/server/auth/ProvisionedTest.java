package dev.nkap.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.testsupport.RecordToString;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiKeyStore.Provisioned}'s {@code toString()}: the token is the API key itself, shown to
 * its caller once and to nothing else.
 */
class ProvisionedTest {

    // --- Provisioned ---------------------------------------------------------------------------

    private static final Map<String, String> PROVISIONED_SECRETS = Map.of(
            "token", "canary-api-key-token-c83f");

    private static ApiKeyStore.Provisioned provisioned() {
        Map<String, String> s = PROVISIONED_SECRETS;
        return new ApiKeyStore.Provisioned(new ApiCredential(UUID.fromString("0d6c7a52-8a3e-4f1b-b0c2-6d5e4f3a2b10"),
                "merchant-1", false), s.get("token"));
    }

    @Test
    @DisplayName("Provisioned.toString() prints none of its credentials: the token is the API key, shown to its caller once")
    void provisioned_prints_no_credential() {
        String text = provisioned().toString();
        PROVISIONED_SECRETS.forEach((field, secret) -> assertThat(text).as(field).doesNotContain(secret));
    }

    @Test
    @DisplayName("Provisioned.toString() still prints the credential it authenticates, which holds no secret")
    void provisioned_still_prints_what_is_not_secret() {
        assertThat(provisioned().toString()).contains("merchantId=merchant-1");
    }

    @Test
    @DisplayName("every Provisioned component is accounted for in toString(): printed in clear or masked by a constant")
    void every_provisioned_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(provisioned(), Set.of("credential"),
                PROVISIONED_SECRETS.keySet(), ApiKeyStore.Provisioned.MASKED);
    }
}
