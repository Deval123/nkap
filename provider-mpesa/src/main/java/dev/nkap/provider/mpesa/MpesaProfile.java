package dev.nkap.provider.mpesa;

import dev.nkap.core.money.Currency;
import java.net.URI;
import java.util.Objects;

/**
 * Everything one installation needs to talk to Safaricom's Daraja API for STK Push.
 *
 * <p>Every field is validated here, so a profile missing a credential fails at startup, not on
 * the first payment.
 *
 * @param baseUrl           e.g. {@code https://sandbox.safaricom.co.ke}
 * @param businessShortCode the shortcode payments are collected to, digits only — sent as a
 *                          JSON number in {@code BusinessShortCode} and as a string in
 *                          {@code PartyB}, as Safaricom's own documented example types them
 * @param passkey           the Lipa Na M-Pesa passkey, hashed into each request's
 *                          {@code Password}
 * @param consumerKey       the app's Consumer Key, exchanged with the secret for a bearer token
 * @param consumerSecret    the app's Consumer Secret
 * @param currency          the currency this installation settles in; a payment in any other
 *                          currency is refused before it is sent
 */
public record MpesaProfile(
        URI baseUrl,
        String businessShortCode,
        String passkey,
        String consumerKey,
        String consumerSecret,
        Currency currency) {

    public MpesaProfile {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.getHost() == null || baseUrl.getScheme() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute URL, was " + baseUrl);
        }
        businessShortCode = requireText(businessShortCode, "businessShortCode");
        if (!businessShortCode.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "businessShortCode must be digits only -- it is sent as a JSON number, was " + businessShortCode);
        }
        passkey = requireText(passkey, "passkey");
        consumerKey = requireText(consumerKey, "consumerKey");
        consumerSecret = requireText(consumerSecret, "consumerSecret");
        Objects.requireNonNull(currency, "currency");
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
