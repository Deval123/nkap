package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The composition issue #116 is about, and issue #185 changed: whether a real submission
 * ever carries a callback URL at all, and whose spelling of
 * {@code /callbacks/{providerId}/{reference}} it uses. Neither adapter is involved here —
 * that half is {@code MtnCollectionsAdapterTest}'s
 * {@code the_callback_url_header_follows_provider_options}, which only ever sees a plain map
 * and does not know or care where it came from.
 */
class PublicBaseUrlTest {

    private static final ReferenceId REFERENCE = ReferenceId.of("2c1f7b1a-1a1a-4a1a-8a1a-1a1a1a1a1a1a");

    @Test
    @DisplayName("unset means no header — providerOptionsFor is empty")
    void unset_is_empty() {
        assertThat(new PublicBaseUrl("").providerOptionsFor(ProviderId.of("mtn-cm"), REFERENCE)).isEmpty();
        assertThat(new PublicBaseUrl(null).providerOptionsFor(ProviderId.of("mtn-cm"), REFERENCE)).isEmpty();
    }

    @Test
    @DisplayName("composes <base>/callbacks/<providerId>/<reference> for the provider and payment actually named")
    void composes_per_provider_and_reference() {
        PublicBaseUrl publicBaseUrl = new PublicBaseUrl("https://gateway.example");

        assertThat(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-cm"), REFERENCE))
                .containsExactly(java.util.Map.entry("callbackUrl",
                        "https://gateway.example/callbacks/mtn-cm/" + REFERENCE));
        assertThat(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-gh"), REFERENCE))
                .containsExactly(java.util.Map.entry("callbackUrl",
                        "https://gateway.example/callbacks/mtn-gh/" + REFERENCE));
    }

    @Test
    @DisplayName("two payments to the same provider compose two different callback URLs")
    void composes_per_reference() {
        PublicBaseUrl publicBaseUrl = new PublicBaseUrl("https://gateway.example");
        ReferenceId other = ReferenceId.newReference();

        assertThat(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-cm"), REFERENCE).get("callbackUrl"))
                .isNotEqualTo(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-cm"), other).get("callbackUrl"));
    }

    @Test
    @DisplayName("a trailing slash on the base does not produce a doubled slash in the composed URL")
    void trailing_slash_is_not_doubled() {
        PublicBaseUrl publicBaseUrl = new PublicBaseUrl("https://gateway.example/");

        assertThat(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-cm"), REFERENCE).get("callbackUrl"))
                .isEqualTo("https://gateway.example/callbacks/mtn-cm/" + REFERENCE);
    }

    @Test
    @DisplayName("set but not an absolute URL fails construction, not the first payment")
    void malformed_fails_at_construction() {
        assertThatThrownBy(() -> new PublicBaseUrl("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nkap.public-base-url");
        assertThatThrownBy(() -> new PublicBaseUrl("gateway.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
