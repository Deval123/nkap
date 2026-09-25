package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.nkap.core.money.Currency;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MtnProfileTest {

    // One helper so each test can vary the single field it is about.
    private static MtnProfile profile(String base, String env, String key, String user, String apiKey,
                                      Currency currency, String country) {
        return new MtnProfile(base == null ? null : URI.create(base), env, key, user, apiKey, currency, country);
    }

    @Test
    @DisplayName("a fully specified profile is accepted")
    void a_complete_profile_is_valid() {
        MtnProfile profile = profile("https://sandbox.momodeveloper.mtn.com", "sandbox",
                "sub-key", "api-user", "api-key", Currency.EUR, "sandbox");
        assertThat(profile.currency()).isEqualTo(Currency.EUR);
        assertThat(profile.targetEnvironment()).isEqualTo("sandbox");
    }

    @Test
    @DisplayName("a blank credential fails on construction, naming the field")
    void a_blank_credential_is_rejected() {
        assertThatThrownBy(() -> profile("https://x.test", "sandbox", "  ", "u", "k", Currency.EUR, "rw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subscriptionKey");
        assertThatThrownBy(() -> profile("https://x.test", "sandbox", "s", null, "k", Currency.EUR, "rw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiUser");
        assertThatThrownBy(() -> profile("https://x.test", "", "s", "u", "k", Currency.EUR, "rw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetEnvironment");
    }

    @Test
    @DisplayName("a null currency or country is rejected")
    void currency_and_country_are_required() {
        assertThatThrownBy(() -> profile("https://x.test", "sandbox", "s", "u", "k", null, "rw"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> profile("https://x.test", "sandbox", "s", "u", "k", Currency.EUR, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("country");
    }

    @Test
    @DisplayName("a base URL that is not absolute is rejected")
    void base_url_must_be_absolute() {
        assertThatThrownBy(() -> new MtnProfile(URI.create("/collection"), "sandbox", "s", "u", "k", Currency.EUR, "rw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    @DisplayName("endpoint() joins paths whether or not the base URL has a trailing slash")
    void endpoint_joins_cleanly() {
        MtnProfile withSlash = profile("https://x.test/", "sandbox", "s", "u", "k", Currency.EUR, "rw");
        MtnProfile withoutSlash = profile("https://x.test", "sandbox", "s", "u", "k", Currency.EUR, "rw");
        assertThat(withSlash.endpoint("/collection/token/").toString()).isEqualTo("https://x.test/collection/token/");
        assertThat(withoutSlash.endpoint("/collection/token/").toString()).isEqualTo("https://x.test/collection/token/");
    }

    // --- edge whitespace: refused, naming the field, never the value --------------------------

    /** Every field requireText guards, in constructor order. */
    private static final List<String> FIELDS = List.of("targetEnvironment", "subscriptionKey", "apiUser", "apiKey", "country");

    /** A valid profile with {@code value} in {@code field} and plain values everywhere else. */
    private static MtnProfile with(String field, String value) {
        return profile("https://x.test",
                field.equals("targetEnvironment") ? value : "sandbox",
                field.equals("subscriptionKey") ? value : "sub-key",
                field.equals("apiUser") ? value : "api-user",
                field.equals("apiKey") ? value : "api-key",
                Currency.EUR,
                field.equals("country") ? value : "cm");
    }

    private static String refusal(String field, String value) {
        Throwable thrown = catchThrowable(() -> with(field, value));
        assertThat(thrown).as(field).isInstanceOf(IllegalArgumentException.class);
        return thrown.getMessage();
    }

    @Test
    @DisplayName("a leading space, a trailing space, and both are each refused, naming the field and the end")
    void edge_spaces_are_refused_naming_the_field_and_the_end() {
        for (String field : FIELDS) {
            assertThat(refusal(field, " value")).startsWith(field + " has leading whitespace");
            assertThat(refusal(field, "value ")).startsWith(field + " has trailing whitespace");
            assertThat(refusal(field, " value ")).startsWith(field + " has leading and trailing whitespace");
        }
    }

    @Test
    @DisplayName("a trailing newline is refused: what a credential file with two newlines leaves after the config tree removes one")
    void a_trailing_newline_is_refused() {
        for (String field : FIELDS) {
            assertThat(refusal(field, "value\n")).startsWith(field + " has trailing whitespace");
        }
    }

    @Test
    @DisplayName("a no-break space (U+00A0) at either end is refused -- strip() and trim() both leave it, so this proves neither alone was used")
    void a_no_break_space_is_refused_which_neither_strip_nor_trim_would_catch() {
        assertThat("value\u00A0".strip()).as("the premise: strip() leaves U+00A0 in place").isEqualTo("value\u00A0");
        for (String field : FIELDS) {
            assertThat(refusal(field, "value\u00A0")).startsWith(field + " has trailing whitespace");
            assertThat(refusal(field, "\u00A0value")).startsWith(field + " has leading whitespace");
        }
    }

    @Test
    @DisplayName("an em space (U+2003) is refused -- strip() removes it and trim() does not, so this proves trim() alone was not used")
    void an_em_space_is_refused_which_trim_would_miss() {
        for (String field : FIELDS) {
            assertThat(refusal(field, "value\u2003")).startsWith(field + " has trailing whitespace");
        }
    }

    @Test
    @DisplayName("a leading byte-order mark (U+FEFF) is refused -- neither isWhitespace nor isSpaceChar reports it, so this pins the format-character clause")
    void a_byte_order_mark_is_refused() {
        assertThat(Character.isWhitespace(0xFEFF) || Character.isSpaceChar(0xFEFF))
                .as("the premise: U+FEFF is neither whitespace nor a space separator").isFalse();
        for (String field : FIELDS) {
            assertThat(refusal(field, "\uFEFF" + "value"))
                    .startsWith(field + " has leading whitespace or an invisible character");
        }
    }

    @Test
    @DisplayName("a leading replacement character (U+FFFD) and a trailing NUL are refused -- what a UTF-16 file read as UTF-8 begins and ends with")
    void a_replacement_character_and_a_nul_are_refused() {
        for (String field : FIELDS) {
            assertThat(refusal(field, "\uFFFD" + "value"))
                    .startsWith(field + " has leading whitespace or an invisible character");
            assertThat(refusal(field, "value" + "\u0000"))
                    .startsWith(field + " has trailing whitespace or an invisible character");
        }
    }

    @Test
    @DisplayName("an interior space is accepted: the rule is about the edges, not about whitespace")
    void an_interior_space_is_accepted() {
        for (String field : FIELDS) {
            assertThatCode(() -> with(field, "val ue")).as(field).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("no refusal carries the value, any prefix of it, its length, or the character found")
    void no_refusal_carries_the_value() {
        String secret = "Zq7Xk9Vw3Rp2Jm";
        for (String field : FIELDS) {
            for (String padded : List.of(" " + secret, secret + " ", " " + secret + " ", secret + "\n",
                    secret + "\u00A0", "\u00A0" + secret, "\uFEFF" + secret, "\uFFFD" + secret,
                    secret + "\u0000")) {
                String message = refusal(field, padded);
                for (int n = 2; n <= secret.length(); n++) {
                    assertThat(message).as("%s: a %d-character prefix of the value", field, n)
                            .doesNotContain(secret.substring(0, n));
                }
                assertThat(message).as("the length").doesNotContain(String.valueOf(secret.length()))
                        .doesNotContain(String.valueOf(padded.length()));
                assertThat(message).as("the character found").doesNotContain("\u00A0").doesNotContain("\n")
                        .doesNotContain("U+").doesNotContain("00A0")
                        .doesNotContain("\uFEFF").doesNotContain("\uFFFD").doesNotContain("\u0000");
                assertThat(message.toLowerCase()).as("the kind of character found")
                        .doesNotContain("byte-order").doesNotContain("bom").doesNotContain("replacement")
                        .doesNotContain("control").doesNotContain("format").doesNotContain("no-break");
            }
        }
    }

    // --- toString -------------------------------------------------------------------------

    /** Recognisable fake credentials, so a leak of any one of them is unmistakable. */
    private static final Map<String, String> SECRETS = Map.of(
            "subscriptionKey", "canary-subscription-key-3e8d",
            "apiUser", "canary-api-user-0c41",
            "apiKey", "canary-api-key-9a7f");

    /** Components toString() prints in clear; every other one must be masked. */
    private static final Set<String> PRINTED = Set.of("baseUrl", "targetEnvironment", "currency", "country");

    private static MtnProfile withCanaries() {
        return new MtnProfile(URI.create("https://sandbox.momodeveloper.mtn.com"), "sandbox",
                SECRETS.get("subscriptionKey"), SECRETS.get("apiUser"), SECRETS.get("apiKey"), Currency.EUR, "rw");
    }

    @Test
    @DisplayName("toString() prints none of the credentials, so a failing assertion or a stray log line cannot leak one")
    void to_string_prints_no_credential() {
        String text = withCanaries().toString();
        SECRETS.forEach((field, secret) -> assertThat(text).as(field).doesNotContain(secret));
    }

    @Test
    @DisplayName("toString() still prints the base URL, the target environment and the country, so it stays useful for debugging")
    void to_string_still_prints_what_is_not_secret() {
        String text = withCanaries().toString();
        assertThat(text).contains("baseUrl=https://sandbox.momodeveloper.mtn.com")
                .contains("targetEnvironment=sandbox")
                .contains("country=rw");
    }

    @Test
    @DisplayName("every record component is accounted for in toString(): printed in clear or masked by a constant, nothing left out")
    void every_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(withCanaries(), PRINTED, SECRETS.keySet(), MtnProfile.MASKED);
    }
}
