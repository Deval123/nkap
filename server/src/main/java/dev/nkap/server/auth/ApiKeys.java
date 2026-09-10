package dev.nkap.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

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
 */
final class ApiKeys {

    static final String PREFIX = "nkap_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    /** A fresh key: {@value #PREFIX} + 32 random bytes, base64url without padding. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
