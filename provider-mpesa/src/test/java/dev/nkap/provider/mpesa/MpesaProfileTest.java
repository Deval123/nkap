package dev.nkap.provider.mpesa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.nkap.core.money.Currency;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MpesaProfile}'s refusals: a blank field, and a field with whitespace at either end,
 * both fail on construction, naming the field and never the value.
 */
class MpesaProfileTest {

    /** Every field requireText guards, in constructor order. */
    private static final List<String> FIELDS = List.of("businessShortCode", "passkey", "consumerKey", "consumerSecret");

    /** A plain value of the right shape for {@code field}: the shortcode must be digits. */
    private static String plain(String field) {
        return field.equals("businessShortCode") ? "174379" : "value";
    }

    /** A valid profile with {@code value} in {@code field} and plain values everywhere else. */
    private static MpesaProfile with(String field, String value) {
        return new MpesaProfile(URI.create("https://x.test"),
                field.equals("businessShortCode") ? value : "174379",
                field.equals("passkey") ? value : "passkey",
                field.equals("consumerKey") ? value : "consumer-key",
                field.equals("consumerSecret") ? value : "consumer-secret",
                Currency.KES);
    }

    private static String refusal(String field, String value) {
        Throwable thrown = catchThrowable(() -> with(field, value));
        assertThat(thrown).as(field).isInstanceOf(IllegalArgumentException.class);
        return thrown.getMessage();
    }

    @Test
    @DisplayName("a fully specified profile is accepted")
    void a_complete_profile_is_valid() {
        assertThat(with("passkey", "passkey").passkey()).isEqualTo("passkey");
    }

    @Test
    @DisplayName("a blank or null field fails on construction, naming the field, as before")
    void blank_and_null_are_refused_as_before() {
        for (String field : FIELDS) {
            assertThat(refusal(field, "  ")).isEqualTo(field + " must not be blank");
            assertThat(refusal(field, "")).isEqualTo(field + " must not be blank");
            assertThat(refusal(field, null)).isEqualTo(field + " must not be blank");
        }
    }

    @Test
    @DisplayName("a leading space, a trailing space, and both are each refused, naming the field and the end")
    void edge_spaces_are_refused_naming_the_field_and_the_end() {
        for (String field : FIELDS) {
            assertThat(refusal(field, " " + plain(field))).startsWith(field + " has leading whitespace");
            assertThat(refusal(field, plain(field) + " ")).startsWith(field + " has trailing whitespace");
            assertThat(refusal(field, " " + plain(field) + " ")).startsWith(field + " has leading and trailing whitespace");
        }
    }

    @Test
    @DisplayName("a trailing newline is refused: what a credential file with two newlines leaves after the config tree removes one")
    void a_trailing_newline_is_refused() {
        for (String field : FIELDS) {
            assertThat(refusal(field, plain(field) + "\n")).startsWith(field + " has trailing whitespace");
        }
    }

    @Test
    @DisplayName("a no-break space (U+00A0) at either end is refused -- strip() and trim() both leave it, so this proves neither alone was used")
    void a_no_break_space_is_refused_which_neither_strip_nor_trim_would_catch() {
        assertThat("value\u00A0".strip()).as("the premise: strip() leaves U+00A0 in place").isEqualTo("value\u00A0");
        for (String field : FIELDS) {
            assertThat(refusal(field, plain(field) + "\u00A0")).startsWith(field + " has trailing whitespace");
            assertThat(refusal(field, "\u00A0" + plain(field))).startsWith(field + " has leading whitespace");
        }
    }

    @Test
    @DisplayName("an em space (U+2003) is refused -- strip() removes it and trim() does not, so this proves trim() alone was not used")
    void an_em_space_is_refused_which_trim_would_miss() {
        for (String field : FIELDS) {
            assertThat(refusal(field, plain(field) + "\u2003")).startsWith(field + " has trailing whitespace");
        }
    }

    @Test
    @DisplayName("a leading byte-order mark (U+FEFF) is refused -- neither isWhitespace nor isSpaceChar reports it, so this pins the format-character clause")
    void a_byte_order_mark_is_refused() {
        assertThat(Character.isWhitespace(0xFEFF) || Character.isSpaceChar(0xFEFF))
                .as("the premise: U+FEFF is neither whitespace nor a space separator").isFalse();
        for (String field : FIELDS) {
            assertThat(refusal(field, "\uFEFF" + plain(field)))
                    .startsWith(field + " has leading whitespace or an invisible character");
        }
    }

    @Test
    @DisplayName("a leading replacement character (U+FFFD) and a trailing NUL are refused -- what a UTF-16 file read as UTF-8 begins and ends with")
    void a_replacement_character_and_a_nul_are_refused() {
        for (String field : FIELDS) {
            assertThat(refusal(field, "\uFFFD" + plain(field)))
                    .startsWith(field + " has leading whitespace or an invisible character");
            assertThat(refusal(field, plain(field) + "\u0000"))
                    .startsWith(field + " has trailing whitespace or an invisible character");
        }
    }

    @Test
    @DisplayName("an interior space is accepted in every credential: the rule is about the edges, not about whitespace")
    void an_interior_space_is_accepted() {
        for (String field : List.of("passkey", "consumerKey", "consumerSecret")) {
            assertThatCode(() -> with(field, "val ue")).as(field).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("no refusal carries the value, any prefix of it, its length, or the character found")
    void no_refusal_carries_the_value() {
        for (String field : FIELDS) {
            String secret = field.equals("businessShortCode") ? "8271645093" : "Zq7Xk9Vw3Rp2Jm";
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

    @Test
    @DisplayName("a shortcode that is not digits is still refused by its own rule")
    void a_non_digit_shortcode_is_still_refused() {
        assertThatThrownBy(() -> with("businessShortCode", "17a379"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digits only");
    }
}
