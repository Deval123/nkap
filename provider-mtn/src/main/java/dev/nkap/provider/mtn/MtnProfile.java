package dev.nkap.provider.mtn;

import dev.nkap.core.money.Currency;
import java.net.URI;
import java.util.Objects;

/**
 * Everything one installation needs to talk to MTN for one product in one country.
 *
 * <p>Each country is its own developer portal with its own user store, and each product
 * (Collections, Disbursements) its own subscription key — so a profile is a whole set of
 * credentials, not a parameter inside a shared account. See ADR 0004.
 *
 * <p>An adapter is built from exactly one profile. Holding several and routing between
 * them is the {@code server} module's job.
 *
 * <p>Every field is validated here. A profile missing a credential fails on construction,
 * at startup, not on the first payment.
 *
 * @param baseUrl            e.g. {@code https://sandbox.momodeveloper.mtn.com}
 * @param targetEnvironment  sent as {@code X-Target-Environment}: {@code sandbox} in the
 *                           sandbox, a country value in production
 * @param subscriptionKey    the Collections {@code Ocp-Apim-Subscription-Key}
 * @param apiUser            the API user id — the UUID chosen when the user was created
 * @param apiKey             the API key exchanged with {@code apiUser} for a bearer token
 * @param currency           the currency this installation settles in; a payment in any
 *                           other currency is refused before it is sent
 * @param country            the country this installation serves, carried for the
 *                           server's routing and for legibility in logs
 */
public record MtnProfile(
        URI baseUrl,
        String targetEnvironment,
        String subscriptionKey,
        String apiUser,
        String apiKey,
        Currency currency,
        String country) {

    public MtnProfile {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.getHost() == null || baseUrl.getScheme() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute URL, was " + baseUrl);
        }
        targetEnvironment = requireText(targetEnvironment, "targetEnvironment");
        subscriptionKey = requireText(subscriptionKey, "subscriptionKey");
        apiUser = requireText(apiUser, "apiUser");
        apiKey = requireText(apiKey, "apiKey");
        Objects.requireNonNull(currency, "currency");
        country = requireText(country, "country");
    }

    /** The base URL with a trailing slash removed, so path joining is unambiguous. */
    URI endpoint(String path) {
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
