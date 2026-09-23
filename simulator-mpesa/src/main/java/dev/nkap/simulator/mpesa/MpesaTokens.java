package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.scenario.TimelineEngine;
import java.security.SecureRandom;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The bearer token {@code GET /oauth/v1/generate} issues, and the check the STK Push routes
 * apply when the declared configuration enforces it.
 *
 * <p>A token is 28 characters — <strong>observed</strong>, 2026-09-18. What the characters
 * are is not recorded; this face's are <strong>modelled</strong>: the expiry, as epoch
 * seconds in base 36 and padded to eight characters, then twenty random alphanumerics. That
 * lets a token carry its own expiry, the way {@code simulator-mtn}'s does, so there is no
 * table of issued tokens to keep.
 *
 * <p>Enforcement is opt-in, as it is for every face: when and whether a credential expires is
 * the core's declared {@code token}; with {@code enforce} off, the {@code Authorization}
 * header is ignored. What Safaricom answers a missing or expired token with is not recorded,
 * so a refusal here is a plain {@code 401} — modelled.
 */
@Component
class MpesaTokens {

    static final int LENGTH = 28;
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TimelineEngine<?, ?, ?> engine;

    MpesaTokens(MpesaScenarioEngine engine) {
        this.engine = engine;
    }

    String issue() {
        long expiry = Instant.now().plus(engine.tokenTtl()).getEpochSecond();
        String stamp = Long.toString(expiry, 36);
        StringBuilder token = new StringBuilder("0".repeat(8 - stamp.length())).append(stamp);
        while (token.length() < LENGTH) {
            token.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return token.toString();
    }

    void require(String authorizationHeader) {
        if (!engine.token().enforce()) {
            return;
        }
        if (!isLive(bearer(authorizationHeader), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "a live bearer token is required: this scenario enforces token expiry");
        }
    }

    static boolean isLive(String token, Instant now) {
        if (token == null || token.length() != LENGTH) {
            return false;
        }
        try {
            return now.isBefore(Instant.ofEpochSecond(Long.parseLong(token.substring(0, 8), 36)));
        } catch (NumberFormatException notOurs) {
            return false;
        }
    }

    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String value = header.strip();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).strip() : value;
    }
}
