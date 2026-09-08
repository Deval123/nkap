package dev.nkap.simulator;

import dev.nkap.simulator.scenario.ScenarioEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MTN hands out a short-lived bearer token before any Collections call. The
 * simulator issues one that is syntactically plausible; its lifetime is declared
 * configuration — set with the rule set, independent of any payment — and is one
 * hour by default. Enforcing expiry mid-flight is issue #9.
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
            "access_token", UUID.randomUUID().toString(),
            "token_type", "access_token",
            "expires_in", (int) engine.tokenTtl().toSeconds());
    }
}
