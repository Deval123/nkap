package dev.nkap.simulator;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bearer token the simulator issues. Shaped like a JWT — three base64url
 * segments — and, like a JWT, self-describing: the payload segment carries the
 * instant the token stops being valid.
 *
 * <p>That is deliberate. Validation reads the expiry straight out of the token,
 * so there is no server-side table of issued tokens to keep — a second unbounded
 * map would be the callback-log bug of issue #16 written twice. It also survives
 * a restart and cannot leak.
 *
 * <p>The token is not signed and nothing verifies a signature: MTN's sandbox has
 * none here, and the simulator's job is to make a token expire, not to do
 * cryptography.
 */
final class SessionToken {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HEADER =
        B64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final Pattern EXP = Pattern.compile("\"exp\":(\\d+)");

    private SessionToken() {
    }

    /** A fresh token that stops being valid {@code ttl} from now. */
    static String issue(Duration ttl) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        String payload = B64.encodeToString(
            ("{\"exp\":" + exp + ",\"jti\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
        byte[] noise = new byte[16];
        RANDOM.nextBytes(noise);
        return HEADER + "." + payload + "." + B64.encodeToString(noise);
    }

    /**
     * The expiry carried by {@code token}, or empty when it is absent, not the
     * shape we issue, or otherwise unreadable — cases an enforcing endpoint all
     * treats as "not authorised".
     */
    static Optional<Instant> expiry(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            return Optional.empty();
        }
        try {
            String payload = new String(B64D.decode(segments[1]), StandardCharsets.UTF_8);
            Matcher exp = EXP.matcher(payload);
            return exp.find()
                ? Optional.of(Instant.ofEpochSecond(Long.parseLong(exp.group(1))))
                : Optional.empty();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /** Whether {@code token} is one we issued and has not yet expired at {@code now}. */
    static boolean isLive(String token, Instant now) {
        return expiry(token).map(now::isBefore).orElse(false);
    }
}
