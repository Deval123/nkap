package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import java.net.URI;
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
}
