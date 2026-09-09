package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderId;
import java.util.Objects;

/**
 * How a configured provider installation is routed: for now, the single currency it
 * settles in.
 *
 * <p>This is derived from <strong>configuration</strong>, not from the adapter. An adapter
 * translates; it does not decide what a deployment serves. A request in a currency this
 * installation does not settle is not a failed payment and not an adapter concern — it is a
 * request the gateway can answer before any payment or idempotency claim exists. Routing by
 * country will extend this record, through the same {@link AdapterRegistry} seam.
 */
public record ProviderRouting(ProviderId provider, Currency currency) {

    public ProviderRouting {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(currency, "currency");
    }
}
