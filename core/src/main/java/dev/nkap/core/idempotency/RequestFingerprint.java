package dev.nkap.core.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A hash of the request body, stored beside the idempotency key.
 *
 * <p>Without it, a client that reuses a key with different content would silently get
 * back the first request's answer. With it, that case is a 409 the client can see and
 * fix.
 */
public record RequestFingerprint(String sha256) {

    public RequestFingerprint {
        Objects.requireNonNull(sha256, "sha256");
    }

    public static RequestFingerprint of(String body) {
        Objects.requireNonNull(body, "body");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            return new RequestFingerprint(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
