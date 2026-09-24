package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderId;
import java.util.Objects;

/**
 * How a configured provider installation is routed: the country {@code POST /payments} names
 * to reach it, the currency it settles in, and the base URL a payment routed to it is actually
 * submitted against.
 *
 * <p>This is derived from <strong>configuration</strong>, not from the adapter. An adapter
 * translates; it does not decide what a deployment serves. A request in a currency this
 * installation does not settle is not a failed payment and not an adapter concern — it is a
 * request the gateway can answer before any payment or idempotency claim exists.
 *
 * <p>{@code country} is what {@code POST /payments}'s own {@code country} field is matched
 * against (issue #82). It used to be recovered from the provider id by a {@code "mtn-"} prefix
 * in {@code PaymentController}; with a second operator (issue #215) the country has to be
 * stated by the installation that serves it, so the registry can tell {@code mpesa-ke} from
 * {@code mtn-cm} without knowing either operator's name.
 *
 * <p>{@code baseUrl} exists so a payment can record which endpoint actually answered it
 * (issue #122) — {@code http://simulator:8081} and MTN's real sandbox both register as
 * {@code mtn-cm} under #82's routing, and nothing else here tells them apart.
 */
public record ProviderRouting(ProviderId provider, String country, Currency currency, String baseUrl) {

    public ProviderRouting {
        Objects.requireNonNull(provider, "provider");
        if (country == null || country.isBlank()) {
            throw new IllegalArgumentException("country must not be blank");
        }
        Objects.requireNonNull(currency, "currency");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
    }
}
