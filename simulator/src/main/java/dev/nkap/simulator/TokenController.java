package dev.nkap.simulator;

import dev.nkap.simulator.scenario.ScenarioEngine;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MTN hands out a short-lived bearer token before any Collections call. The
 * simulator issues a {@link SessionToken} that carries its own expiry, with an
 * {@code expires_in} taken from the declared lifetime (one hour by default).
 *
 * <p>This endpoint always issues a fresh token and is never itself protected —
 * it is how a client that has just been told 401 gets back in.
 */
@RestController
public class TokenController {

    private final ScenarioEngine engine;

    TokenController(ScenarioEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/collection/token/")
    public Map<String, Object> token() {
        return Map.of(
            "access_token", SessionToken.issue(engine.tokenTtl()),
            "token_type", "access_token",
            "expires_in", (int) engine.tokenTtl().toSeconds());
    }
}
