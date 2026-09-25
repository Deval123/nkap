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

    /**
     * A present value, with nothing invisible at either end.
     *
     * <p>Edge whitespace is refused, not stripped. It would be sent to the operator exactly as
     * configured, be refused there on every request, and show in no log, because no message
     * here prints a value. Stripping it silently would rewrite a credential on the deployer's
     * behalf, and nothing here can know the whitespace is not part of it. So the gateway refuses
     * to start. The message names the field and which end is affected, and never the value, its
     * length or the character found.
     *
     * <p>"Whitespace" is what {@link Character#isWhitespace} says, which is what
     * {@link String#strip()} removes, plus {@link Character#isSpaceChar}. That adds the no-break
     * spaces (U+00A0, U+2007, U+202F), which {@code strip()} leaves in place, and a no-break space
     * pasted out of a web portal is the likeliest way one gets here. {@link String#trim()} would
     * miss more still: it knows nothing above U+0020.
     *
     * <p>Duplicated in {@code MtnProfile} on purpose. Lifting it into {@code provider-api}
     * or {@code core} would make it public API of a published module, which only a major release
     * could change. It would also suggest every adapter must validate its credentials this exact
     * way, which is the adapter author's decision. Revisit if a third operator arrives.
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        boolean leading = isEdgeWhitespace(value.codePointAt(0));
        boolean trailing = isEdgeWhitespace(value.codePointBefore(value.length()));
        if (leading || trailing) {
            String where = leading && trailing ? "leading and trailing" : leading ? "leading" : "trailing";
            throw new IllegalArgumentException(field + " has " + where + " whitespace; remove it. It is refused"
                    + " rather than stripped: the value is used exactly as configured, and nothing here can know"
                    + " the whitespace is not part of it.");
        }
        return value;
    }

    private static boolean isEdgeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
