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

    /**
     * Every component, in declaration order, with the three credentials replaced by a constant
     * marker.
     *
     * <p>The generated {@code toString()} prints every component, the passkey included. No code is
     * meant to print a profile, but AssertJ prints the actual object when an assertion on it fails,
     * and this repository's CI logs are public. The passkey is recoverable from any single
     * request, and no revocation point has been observed (see {@code docs/security-notes.md}),
     * so a leaked one cannot simply be rotated. The marker never varies with the value: no
     * length, prefix, suffix or hash. The shortcode is printed because it is not secret;
     * Safaricom's own documented example carries {@code 174379}.
     */
    @Override
    public String toString() {
        return "MpesaProfile[baseUrl=" + baseUrl
                + ", businessShortCode=" + businessShortCode
                + ", passkey=" + MASKED
                + ", consumerKey=" + MASKED
                + ", consumerSecret=" + MASKED
                + ", currency=" + currency + "]";
    }

    /** What {@link #toString()} prints in place of a credential. */
    static final String MASKED = "***";

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
     * <p>An invisible character at the edge is refused, not stripped. It would be sent to the
     * operator exactly as configured, be refused there on every request, and show in no log,
     * because no message here prints a value. Stripping it silently would rewrite a credential
     * on the deployer's behalf, and nothing here can know it is not part of the value. So the
     * gateway refuses to start. The message names the field and which end is affected, and
     * never the value, its length, the character found or its kind.
     *
     * <p>Refused at either end, measured on Java 21 rather than assumed:
     * <ul>
     *   <li>{@link Character#isWhitespace}, which is what {@link String#strip()} removes;</li>
     *   <li>{@link Character#isSpaceChar}, which adds the no-break spaces (U+00A0, U+2007,
     *       U+202F) that {@code strip()} leaves in place, the likeliest result of pasting from
     *       a web portal. {@link String#trim()} would miss more still: it knows nothing above
     *       U+0020;</li>
     *   <li>format characters, among them U+FEFF, the byte-order mark. Neither of the two above
     *       reports it, and a credential file saved as UTF-8 with a byte-order mark keeps it at
     *       the start of the value once read;</li>
     *   <li>control characters, and U+FFFD, the replacement character. A UTF-16 file read as
     *       UTF-8 begins with U+FFFD, where its byte-order mark could not be decoded, and ends
     *       with U+0000; neither is whitespace, and U+FFFD at all means decoding failed.</li>
     * </ul>
     * Only the ends are checked. An interior space is a legitimate character as far as this
     * check knows.
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
        boolean leading = isInvisible(value.codePointAt(0));
        boolean trailing = isInvisible(value.codePointBefore(value.length()));
        if (leading || trailing) {
            String where = leading && trailing ? "leading and trailing" : leading ? "leading" : "trailing";
            throw new IllegalArgumentException(field + " has " + where + " whitespace or an invisible character;"
                    + " remove it. It is refused rather than stripped: the value is used exactly as configured,"
                    + " and nothing here can know that character is not part of it.");
        }
        return value;
    }

    private static boolean isInvisible(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || Character.isISOControl(codePoint)
                || codePoint == 0xFFFD;
    }
}
