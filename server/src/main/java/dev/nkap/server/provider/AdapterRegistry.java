package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.Optional;

/**
 * Resolves a {@link ProviderAdapter} by its {@link ProviderId}, and answers what the
 * configured installation for a provider will serve.
 *
 * <p>A seam, deliberately. A second operator and multi-country routing both arrive
 * through it; injecting a concrete adapter type instead would mean touching every call
 * site to add either.
 */
public interface AdapterRegistry {

    Optional<ProviderAdapter> find(ProviderId id);

    /** The adapter for {@code id}, or {@link NoAdapterConfiguredException} if none is configured. */
    ProviderAdapter require(ProviderId id);

    /**
     * The currency the configured installation for {@code id} settles in, when routing for
     * it is configured.
     *
     * <p>A payment in any other currency cannot be served by that deployment: no operator
     * was asked, nothing is unknown, and the caller simply addressed an installation that
     * does not serve that currency. The gateway rejects it before a payment or an
     * idempotency claim exists.
     */
    Optional<Currency> settlementCurrency(ProviderId id);
}
