package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfiguredAdapterRegistryTest {

    private static ProviderAdapter adapterFor(String id) {
        ProviderAdapter adapter = mock(ProviderAdapter.class);
        when(adapter.id()).thenReturn(ProviderId.of(id));
        return adapter;
    }

    @Test
    @DisplayName("require returns the adapter registered for a provider id")
    void require_resolves_a_configured_adapter() {
        ProviderAdapter mtn = adapterFor("mtn");
        AdapterRegistry registry = new ConfiguredAdapterRegistry(List.of(mtn), List.of());

        assertThat(registry.require(ProviderId.of("mtn"))).isSameAs(mtn);
        assertThat(registry.find(ProviderId.of("mtn"))).contains(mtn);
    }

    @Test
    @DisplayName("require on an unconfigured provider throws its own type, with the configured ids kept for the log")
    void require_on_an_unknown_provider_throws() {
        AdapterRegistry registry = new ConfiguredAdapterRegistry(List.of(adapterFor("mtn")), List.of());

        assertThatThrownBy(() -> registry.require(ProviderId.of("orange")))
                .isInstanceOf(NoAdapterConfiguredException.class)
                .hasMessageContaining("orange")
                .hasMessageContaining("mtn");
        assertThat(registry.find(ProviderId.of("orange"))).isEmpty();
    }

    @Test
    @DisplayName("two adapters claiming the same provider id is a configuration error")
    void a_duplicate_provider_id_is_rejected() {
        assertThatThrownBy(() -> new ConfiguredAdapterRegistry(
                List.of(adapterFor("mtn"), adapterFor("mtn")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mtn");
    }

    @Test
    @DisplayName("settlementCurrency reports the currency configured for a provider, and nothing for an unrouted one")
    void settlement_currency_comes_from_the_configured_routing() {
        ProviderId mtn = ProviderId.of("mtn");
        AdapterRegistry registry = new ConfiguredAdapterRegistry(
                List.of(adapterFor("mtn")), List.of(new ProviderRouting(mtn, "cm", Currency.EUR, "http://simulator:8081")));

        assertThat(registry.settlementCurrency(mtn)).contains(Currency.EUR);
        assertThat(registry.settlementCurrency(ProviderId.of("orange"))).isEmpty();
    }

    @Test
    @DisplayName("two routes disagreeing on a provider's currency is a configuration error")
    void conflicting_routes_are_rejected() {
        ProviderId mtn = ProviderId.of("mtn");
        assertThatThrownBy(() -> new ConfiguredAdapterRegistry(List.of(adapterFor("mtn")),
                List.of(new ProviderRouting(mtn, "cm", Currency.EUR, "http://simulator:8081"),
                        new ProviderRouting(mtn, "cm", Currency.XAF, "http://simulator:8081"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mtn");
    }

    @Test
    @DisplayName("settlementEndpoint reports the base URL configured for a provider, and nothing for an unrouted one — issue #122")
    void settlement_endpoint_comes_from_the_configured_routing() {
        ProviderId mtn = ProviderId.of("mtn");
        AdapterRegistry registry = new ConfiguredAdapterRegistry(
                List.of(adapterFor("mtn")), List.of(new ProviderRouting(mtn, "cm", Currency.EUR, "http://simulator:8081")));

        assertThat(registry.settlementEndpoint(mtn)).contains("http://simulator:8081");
        assertThat(registry.settlementEndpoint(ProviderId.of("orange"))).isEmpty();
    }

    @Test
    @DisplayName("two routes disagreeing on a provider's base URL is a configuration error")
    void conflicting_endpoints_are_rejected() {
        ProviderId mtn = ProviderId.of("mtn");
        assertThatThrownBy(() -> new ConfiguredAdapterRegistry(List.of(adapterFor("mtn")),
                List.of(new ProviderRouting(mtn, "cm", Currency.EUR, "http://simulator:8081"),
                        new ProviderRouting(mtn, "cm", Currency.EUR, "https://sandbox.momodeveloper.mtn.com"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mtn");
    }
    @Test
    @DisplayName("a country routes to the installation that states it, whichever operator that is")
    void a_country_routes_to_the_installation_that_claims_it() {
        AdapterRegistry registry = new ConfiguredAdapterRegistry(
                List.of(adapterFor("mtn-cm"), adapterFor("mpesa-ke")),
                List.of(new ProviderRouting(ProviderId.of("mtn-cm"), "cm", Currency.XAF, "http://mtn"),
                        new ProviderRouting(ProviderId.of("mpesa-ke"), "ke", Currency.KES, "http://mpesa")));

        assertThat(registry.providerForCountry("cm")).contains(ProviderId.of("mtn-cm"));
        assertThat(registry.providerForCountry(" KE ")).contains(ProviderId.of("mpesa-ke"));
        assertThat(registry.providerForCountry("gh")).isEmpty();
    }

    @Test
    @DisplayName("two installations claiming one country is refused at startup: the request names nothing else to choose by")
    void two_installations_claiming_one_country_are_rejected() {
        assertThatThrownBy(() -> new ConfiguredAdapterRegistry(
                List.of(adapterFor("mtn-ke"), adapterFor("mpesa-ke")),
                List.of(new ProviderRouting(ProviderId.of("mtn-ke"), "ke", Currency.KES, "http://mtn"),
                        new ProviderRouting(ProviderId.of("mpesa-ke"), "KE", Currency.KES, "http://mpesa"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mtn-ke")
                .hasMessageContaining("mpesa-ke")
                .hasMessageContaining("country");
    }
}
