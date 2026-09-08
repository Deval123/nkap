package dev.nkap.server.provider;

import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every {@link ProviderAdapter} bean in the context, indexed by {@link ProviderAdapter#id()}.
 */
@Component
public final class ConfiguredAdapterRegistry implements AdapterRegistry {

    private final Map<ProviderId, ProviderAdapter> byId;

    public ConfiguredAdapterRegistry(List<ProviderAdapter> adapters) {
        Map<ProviderId, ProviderAdapter> index = new LinkedHashMap<>();
        for (ProviderAdapter adapter : adapters) {
            ProviderAdapter clash = index.putIfAbsent(adapter.id(), adapter);
            if (clash != null) {
                throw new IllegalStateException("two adapters both claim provider id " + adapter.id());
            }
        }
        this.byId = Map.copyOf(index);
    }

    @Override
    public Optional<ProviderAdapter> find(ProviderId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public ProviderAdapter require(ProviderId id) {
        ProviderAdapter adapter = byId.get(id);
        if (adapter == null) {
            throw new NoSuchElementException("no adapter is configured for provider " + id
                    + "; configured: " + byId.keySet());
        }
        return adapter;
    }
}
