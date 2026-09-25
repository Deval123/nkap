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

    /**
     * Every component, in declaration order, with the three credentials replaced by a constant
     * marker.
     *
     * <p>The generated {@code toString()} prints every component, the API key and the
     * subscription key included. No code is meant to print a profile, but AssertJ prints
     * the actual object when an assertion on it fails, and this repository's CI logs are
     * public. The marker never varies with the value: no length, prefix, suffix or hash.
     */
    @Override
    public String toString() {
        return "MtnProfile[baseUrl=" + baseUrl
                + ", targetEnvironment=" + targetEnvironment
                + ", subscriptionKey=" + MASKED
                + ", apiUser=" + MASKED
                + ", apiKey=" + MASKED
                + ", currency=" + currency
                + ", country=" + country + "]";
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
     * <p>Duplicated in {@code MpesaProfile} on purpose. Lifting it into {@code provider-api}
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
