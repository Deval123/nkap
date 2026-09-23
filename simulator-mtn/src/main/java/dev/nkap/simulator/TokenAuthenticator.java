package dev.nkap.simulator;

import dev.nkap.simulator.scenario.ScenarioEngine;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enforces the bearer token on the operator endpoints — but only when the
 * declared configuration asks for it: {@code {"token": {"enforce": true}}}.
 *
 * <p><strong>Enforcement is opt-in on purpose.</strong> Always requiring
 * authentication would be faithful to MTN, and would also turn every existing
 * test red — none of them authenticate — and make every README {@code curl} a
 * two-step affair. The simulator trades that fidelity for approachability. With
 * {@code enforce} off, which is the default, the {@code Authorization} header is
 * ignored exactly as it always was. Someone will eventually argue the simulator
 * "should" always enforce; this paragraph is the answer, so it is not
 * re-litigated.
 */
@Component
class TokenAuthenticator {

    private final ScenarioEngine engine;

    TokenAuthenticator(ScenarioEngine engine) {
        this.engine = engine;
    }

    /**
     * Returns silently when enforcement is off, or on and the token is live.
     * Otherwise — no token, a token we did not issue, or an expired one — 401.
     * Callers apply this <em>after</em> request-shape validation (a malformed
     * {@code X-Reference-Id} is still a 400, not a 401) and before consulting
     * the scenario.
     */
    void require(String authorizationHeader) {
        if (!engine.token().enforce()) {
            return;
        }
        if (!SessionToken.isLive(bearerToken(authorizationHeader), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "a live bearer token is required: this scenario enforces token expiry");
        }
    }

    private static String bearerToken(String header) {
        if (header == null) {
            return null;
        }
        String value = header.strip();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).strip() : value;
    }
}
