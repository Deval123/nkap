package dev.nkap.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #86's correction: {@code PostgresApiKeyStore.provisionWithToken} refuses a token
 * that does not have this shape. That is a floor, not proof of entropy — it catches a
 * truncated value or a short human passphrase, but a string a human pads out to the right
 * length passes exactly like a generated one, which {@link #the_demo_token_is_well_formed}
 * demonstrates on purpose. Only a token {@link ApiKeys#newToken()} actually produced is known
 * to carry the 256 bits {@code ApiKeys}'s own javadoc relies on; this check cannot tell a
 * padded guess from a real one, only a short one from either. Written against
 * {@link ApiKeys#isWellFormed} directly, without a database, because the rule itself has
 * nothing to do with storage.
 */
class ApiKeysTest {

    @Test
    @DisplayName("a token newToken() produces is well-formed")
    void a_generated_token_is_well_formed() {
        assertThat(ApiKeys.isWellFormed(ApiKeys.newToken())).isTrue();
    }

    @Test
    @DisplayName("a short human guess is refused for its length, not for being a guess")
    void a_short_guess_is_not_well_formed() {
        // What actually fails these is the length, not the fact that a human chose them --
        // the_demo_token_is_well_formed below is proof: it is exactly as human-chosen as
        // these, and it passes, because a human padded it out to the right length instead
        // of leaving it short.
        assertThat(ApiKeys.isWellFormed("nkap_letmein")).isFalse();
        assertThat(ApiKeys.isWellFormed("letmein")).isFalse();
        assertThat(ApiKeys.isWellFormed("")).isFalse();
        assertThat(ApiKeys.isWellFormed(null)).isFalse();
    }

    @Test
    @DisplayName("one character short or long of newToken()'s length is not well-formed")
    void the_wrong_length_is_not_well_formed() {
        String token = ApiKeys.newToken();
        assertThat(ApiKeys.isWellFormed(token.substring(0, token.length() - 1))).isFalse();
        assertThat(ApiKeys.isWellFormed(token + "a")).isFalse();
    }

    @Test
    @DisplayName("compose.yaml's own fixed demo token is well-formed on purpose")
    void the_demo_token_is_well_formed() {
        // It has to be: nkap-standalone.compose.yaml's key-init no longer accepts
        // --nkap.apikey.token at all, but compose.yaml's still does, and this is the value it
        // passes -- deliberately shaped like a value ApiKeys.newToken() could have produced,
        // with the fake part spelled out in the part of it a human reads.
        assertThat(ApiKeys.isWellFormed("nkap_demo-key-not-for-production-000000000000000")).isTrue();
    }
}
