package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.provider.ProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The composition issue #116 is about: whether a real submission ever carries a callback
 * URL at all, and whose spelling of {@code /callbacks/{providerId}} it uses. Neither adapter
 * is involved here — that half is {@code MtnCollectionsAdapterTest}'s
 * {@code the_callback_url_header_follows_provider_options}, which only ever sees a plain map
 * and does not know or care where it came from.
 */
class PublicBaseUrlTest {

    @Test
    @DisplayName("unset means no header — providerOptionsFor is empty")
    void unset_is_empty() {
        assertThat(new PublicBaseUrl("").providerOptionsFor(ProviderId.of("mtn-cm"))).isEmpty();
        assertThat(new PublicBaseUrl(null).providerOptionsFor(ProviderId.of("mtn-cm"))).isEmpty();
    }

    @Test
    @DisplayName("composes <base>/callbacks/<providerId> for the provider actually named — not hardcoded to one")
    void composes_per_provider() {
        PublicBaseUrl publicBaseUrl = new PublicBaseUrl("https://gateway.example");

        assertThat(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-cm")))
                .containsExactly(java.util.Map.entry("callbackUrl", "https://gateway.example/callbacks/mtn-cm"));
        assertThat(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-gh")))
                .containsExactly(java.util.Map.entry("callbackUrl", "https://gateway.example/callbacks/mtn-gh"));
    }

    @Test
    @DisplayName("a trailing slash on the base does not produce a doubled slash in the composed URL")
    void trailing_slash_is_not_doubled() {
        PublicBaseUrl publicBaseUrl = new PublicBaseUrl("https://gateway.example/");

        assertThat(publicBaseUrl.providerOptionsFor(ProviderId.of("mtn-cm")).get("callbackUrl"))
                .isEqualTo("https://gateway.example/callbacks/mtn-cm");
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
