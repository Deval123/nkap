package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.testsupport.RecordToString;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MtnTokenCache.Token}'s {@code toString()}: the bearer token is a live credential until it
 * expires, so it is masked like the profile's.
 */
class MtnTokenTest {

    // --- Token ---------------------------------------------------------------------------

    private static final Map<String, String> TOKEN_SECRETS = Map.of(
            "value", "canary-bearer-mtn-8b1a");

    private static MtnTokenCache.Token token() {
        Map<String, String> s = TOKEN_SECRETS;
        return new MtnTokenCache.Token(s.get("value"), Instant.parse("2026-09-25T14:00:00Z"));
    }

    @Test
    @DisplayName("Token.toString() prints none of its credentials: the bearer token acts as the installation until it expires")
    void token_prints_no_credential() {
        String text = token().toString();
        TOKEN_SECRETS.forEach((field, secret) -> assertThat(text).as(field).doesNotContain(secret));
    }

    @Test
    @DisplayName("Token.toString() still prints when the token expires")
    void token_still_prints_what_is_not_secret() {
        assertThat(token().toString()).contains("expiresAt=2026-09-25T14:00:00Z");
    }

    @Test
    @DisplayName("every Token component is accounted for in toString(): printed in clear or masked by a constant")
    void every_token_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(token(), Set.of("expiresAt"),
                TOKEN_SECRETS.keySet(), MtnTokenCache.Token.MASKED);
    }
}
