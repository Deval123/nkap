package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import java.util.Optional;
import java.util.Set;

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

    /**
     * The base URL a payment routed to {@code id} is actually submitted against, when
     * routing for it is configured — {@code http://simulator:8081} for a stack pointed at
     * the simulator, MTN's real sandbox or production host otherwise. Recorded on the
     * payment itself so a simulated settlement and a real one stop being byte-identical in
     * provenance (issue #122); {@code provider_id} alone cannot do this, since it is derived
     * from the installation's country (#82) and is the same either way.
     */
    Optional<String> settlementEndpoint(ProviderId id);

    /**
     * The installation {@code POST /payments}'s {@code country} routes to, whichever operator
     * it belongs to — {@code cm} to {@code mtn-cm}, {@code ke} to {@code mpesa-ke} — matched
     * case-insensitively. Empty when no configured installation claims that country.
     *
     * <p>At most one installation ever claims a country: two that do are refused at startup,
     * because {@code country} is the only thing a request names, and the gateway picking
     * between two operators for the same country would be a guess about whose money moves.
     */
    Optional<ProviderId> providerForCountry(String country);

    /**
     * Every provider id this deployment has an adapter for. For a caller who named one that
     * is not configured — a request routing by country (issue #82) is the first place that
     * can be a client mistake rather than a server misconfiguration, and the answer should
     * say what is actually configured.
     */
    Set<ProviderId> configuredProviders();
}
