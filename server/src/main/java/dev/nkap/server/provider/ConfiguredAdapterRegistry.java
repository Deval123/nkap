package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every {@link ProviderAdapter} bean in the context, indexed by {@link ProviderAdapter#id()},
 * with the routing declared for each provider in configuration.
 */
@Component
public final class ConfiguredAdapterRegistry implements AdapterRegistry {

    private final Map<ProviderId, ProviderAdapter> byId;
    private final Map<ProviderId, Currency> settlementCurrencies;

    public ConfiguredAdapterRegistry(List<ProviderAdapter> adapters, List<ProviderRouting> routes) {
        Map<ProviderId, ProviderAdapter> index = new LinkedHashMap<>();
        for (ProviderAdapter adapter : adapters) {
            ProviderAdapter clash = index.putIfAbsent(adapter.id(), adapter);
            if (clash != null) {
                throw new IllegalStateException("two adapters both claim provider id " + adapter.id());
            }
        }
        this.byId = Map.copyOf(index);

        Map<ProviderId, Currency> currencies = new LinkedHashMap<>();
        for (ProviderRouting route : routes) {
            Currency clash = currencies.putIfAbsent(route.provider(), route.currency());
            if (clash != null && clash != route.currency()) {
                throw new IllegalStateException("two routes disagree on the currency for provider " + route.provider());
            }
        }
        this.settlementCurrencies = Map.copyOf(currencies);
    }

    @Override
    public Optional<ProviderAdapter> find(ProviderId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public ProviderAdapter require(ProviderId id) {
        ProviderAdapter adapter = byId.get(id);
        if (adapter == null) {
            throw new NoAdapterConfiguredException(id, byId.keySet());
        }
        return adapter;
    }

    @Override
    public Optional<Currency> settlementCurrency(ProviderId id) {
        return Optional.ofNullable(settlementCurrencies.get(id));
    }
}
