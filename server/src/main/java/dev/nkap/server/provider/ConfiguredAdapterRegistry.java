package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Every {@link ProviderAdapter} bean in the context, indexed by {@link ProviderAdapter#id()},
 * with the routing declared for each provider in configuration.
 */
@Component
public final class ConfiguredAdapterRegistry implements AdapterRegistry {

    private final Map<ProviderId, ProviderAdapter> byId;
    private final Map<ProviderId, Currency> settlementCurrencies;
    private final Map<ProviderId, String> settlementEndpoints;
    private final Map<String, ProviderId> byCountry;

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
        Map<ProviderId, String> endpoints = new LinkedHashMap<>();
        Map<String, ProviderId> countries = new LinkedHashMap<>();
        for (ProviderRouting route : routes) {
            ProviderId countryClash = countries.putIfAbsent(normalised(route.country()), route.provider());
            if (countryClash != null && !countryClash.equals(route.provider())) {
                throw new IllegalStateException("two installations, " + countryClash + " and " + route.provider()
                        + ", both claim country '" + route.country() + "'. POST /payments routes on the country "
                        + "alone, so it could not tell which one a payment is meant for; configure only one.");
            }
            Currency clash = currencies.putIfAbsent(route.provider(), route.currency());
            if (clash != null && clash != route.currency()) {
                throw new IllegalStateException("two routes disagree on the currency for provider " + route.provider());
            }
            String endpointClash = endpoints.putIfAbsent(route.provider(), route.baseUrl());
            if (endpointClash != null && !endpointClash.equals(route.baseUrl())) {
                throw new IllegalStateException("two routes disagree on the base URL for provider " + route.provider());
            }
        }
        this.settlementCurrencies = Map.copyOf(currencies);
        this.settlementEndpoints = Map.copyOf(endpoints);
        this.byCountry = Map.copyOf(countries);
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

    @Override
    public Optional<String> settlementEndpoint(ProviderId id) {
        return Optional.ofNullable(settlementEndpoints.get(id));
    }

    @Override
    public Optional<ProviderId> providerForCountry(String country) {
        if (country == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byCountry.get(normalised(country)));
    }

    @Override
    public Set<ProviderId> configuredProviders() {
        return byId.keySet();
    }

    private static String normalised(String country) {
        return country.strip().toLowerCase(Locale.ROOT);
    }
}
