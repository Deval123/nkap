package dev.nkap.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Generates API keys and hashes them for storage.
 *
 * <p>A key is {@value #PREFIX} followed by 256 bits from {@link SecureRandom}, base64url.
 * The prefix is for a human recognising a key at a glance; it is never a reason to log one.
 *
 * <p>The stored form is a plain <strong>SHA-256</strong>, hex. Not bcrypt, not argon2, and
 * that is deliberate: a slow KDF exists to make <em>low-entropy</em> secrets — passwords —
 * expensive to guess. A key generated here already carries 256 bits of entropy; there is
 * nothing to slow down. A fast hash is the correct tool. The lookup is by exact hash match,
 * so it is also constant-time in the database's index, not a scan of every row.
 *
 * <p>That premise only holds for a token this class produced. {@link #isWellFormed} lets a
 * caller taking a token from the outside — {@link PostgresApiKeyStore#provisionWithToken}
 * does, for {@code --nkap.apikey.token} — reject one that is obviously not that: the wrong
 * length, the wrong prefix, drawn from the wrong alphabet. It is a floor, not a substitute
 * for the premise above: nothing stops a human from padding a chosen string out to this
 * exact shape ({@code compose.yaml}'s own demo token does, on purpose), so a token that
 * passes is not thereby known to carry any entropy at all — only a token this class actually
 * produced is (issue #86's correction).
 */
final class ApiKeys {

    static final String PREFIX = "nkap_";

    private static final SecureRandom RANDOM = new SecureRandom();

    // 32 random bytes, base64url without padding, is always exactly 43 characters
    // (ceil(32 * 8 / 6)) drawn from base64url's own alphabet.
    private static final Pattern WELL_FORMED = Pattern.compile("^" + PREFIX + "[A-Za-z0-9_-]{43}$");

    private ApiKeys() {
    }

    /** A fresh key: {@value #PREFIX} + 32 random bytes, base64url without padding. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Whether {@code token} has the shape {@link #newToken} produces: the right prefix, the
     * right length, drawn from base64url's alphabet. A floor, not a guarantee of entropy —
     * it catches a truncated token, a bare merchant name, or a short human passphrase, but a
     * string deliberately padded out to this exact shape passes just as a generated one does
     * ({@code compose.yaml}'s own demo token, {@code
     * nkap_demo-key-not-for-production-000000000000000}, is exactly that, on purpose). Only a
     * token this class actually produced is known to carry the 256 bits this class's own
     * javadoc relies on.
     */
    static boolean isWellFormed(String token) {
        return token != null && WELL_FORMED.matcher(token).matches();
    }

    /** The lowercase-hex SHA-256 of {@code token} — the only form that is stored. */
    static String hash(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    /** Whether {@code header} is a well-formed {@code Authorization: Bearer <token>} value, returning the token or {@code null}. */
    static String bearerToken(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.strip();
        if (trimmed.length() < 8 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = trimmed.substring(7).strip();
        return token.isEmpty() ? null : token;
    }
}
