package dev.nkap.server.provider;

import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.Optional;

/**
 * Resolves a {@link ProviderAdapter} by its {@link ProviderId}.
 *
 * <p>A seam, deliberately. A second operator and multi-country routing both arrive
 * through it; injecting a concrete adapter type instead would mean touching every call
 * site to add either.
 */
public interface AdapterRegistry {

    Optional<ProviderAdapter> find(ProviderId id);

    /** The adapter for {@code id}, or an unchecked failure if none is configured. */
    ProviderAdapter require(ProviderId id);
}
