package dev.nkap.server.outbox;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over a timestamp and the body, the timestamp inside the signed material so a
 * captured request cannot be replayed later — both facts go in one header, {@value #HEADER}:
 * {@code t=<epoch-seconds>,v1=<hex-hmac>}.
 *
 * <p>Signing what is sent ({@link #sign}) and verifying what is received ({@link #verify})
 * live in the same class deliberately: a merchant re-implementing this in another language
 * needs exactly this pair, and keeping them together is what proves they agree with each
 * other — {@code WebhookSignerTest} calls {@link #sign} and feeds the header straight into
 * {@link #verify}.
 */
public final class WebhookSigner {

    /** The header {@link #sign} produces and {@link #verify} reads. */
    public static final String HEADER = "Nkap-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    /** {@code t=<epoch-seconds>,v1=<hex-hmac of "<epoch-seconds>.<body>">}. */
    public static String sign(String secret, Instant timestamp, String body) {
        long epochSeconds = timestamp.getEpochSecond();
        String signedPayload = epochSeconds + "." + body;
        String hex = HexFormat.of().formatHex(hmac(secret, signedPayload));
        return "t=" + epochSeconds + ",v1=" + hex;
    }

    /**
     * Whether {@code header} is a signature of {@code body} by {@code secret}, timestamped
     * within {@code tolerance} of {@code now}. Both conditions must hold: a signature that
     * verifies but is older than {@code tolerance} fails, the way a captured request replayed
     * an hour later must.
     */
    public static boolean verify(String secret, String header, String body, Instant now, Duration tolerance) {
        Parsed parsed = parse(header);
        if (parsed == null) {
            return false;
        }
        Instant timestamp = Instant.ofEpochSecond(parsed.epochSeconds());
        if (Duration.between(timestamp, now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        String expectedHex = HexFormat.of().formatHex(hmac(secret, parsed.epochSeconds() + "." + body));
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII), parsed.hex().getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", impossible);
        }
    }

    private record Parsed(long epochSeconds, String hex) {
    }

    /** {@code t=<digits>,v1=<hex>} — anything else, including a missing field, is not a signature. */
    private static Parsed parse(String header) {
        if (header == null) {
            return null;
        }
        Long epochSeconds = null;
        String hex = null;
        for (String part : header.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    epochSeconds = Long.parseLong(kv[1]);
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            } else if (kv[0].equals("v1")) {
                hex = kv[1];
            }
        }
        return epochSeconds == null || hex == null ? null : new Parsed(epochSeconds, hex);
    }
}
