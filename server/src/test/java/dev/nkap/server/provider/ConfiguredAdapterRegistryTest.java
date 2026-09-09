package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.List;
import java.util.NoSuchElementException;
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
    @DisplayName("require on an unconfigured provider fails loudly, naming what is configured")
    void require_on_an_unknown_provider_throws() {
        AdapterRegistry registry = new ConfiguredAdapterRegistry(List.of(adapterFor("mtn")), List.of());

        assertThatThrownBy(() -> registry.require(ProviderId.of("orange")))
                .isInstanceOf(NoSuchElementException.class)
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
                List.of(adapterFor("mtn")), List.of(new ProviderRouting(mtn, Currency.EUR)));

        assertThat(registry.settlementCurrency(mtn)).contains(Currency.EUR);
        assertThat(registry.settlementCurrency(ProviderId.of("orange"))).isEmpty();
    }

    @Test
    @DisplayName("two routes disagreeing on a provider's currency is a configuration error")
    void conflicting_routes_are_rejected() {
        ProviderId mtn = ProviderId.of("mtn");
        assertThatThrownBy(() -> new ConfiguredAdapterRegistry(List.of(adapterFor("mtn")),
                List.of(new ProviderRouting(mtn, Currency.EUR), new ProviderRouting(mtn, Currency.XAF))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mtn");
    }
}
