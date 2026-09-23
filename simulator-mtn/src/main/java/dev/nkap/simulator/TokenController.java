package dev.nkap.simulator;

import dev.nkap.simulator.scenario.ScenarioEngine;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MTN hands out a short-lived bearer token before any Collections <em>or</em> Disbursements
 * call. Each product has its own token endpoint ({@code /collection/token/},
 * {@code /disbursement/token/}) because each is a separate product with its own credentials;
 * the simulator does not validate credentials, so the two endpoints behave identically —
 * each issues a fresh {@link SessionToken} with an {@code expires_in} from the declared
 * lifetime (one hour by default).
 *
 * <p>Neither endpoint is itself protected — it is how a client that has just been told 401
 * gets back in.
 */
@RestController
public class TokenController {

    private final ScenarioEngine engine;

    TokenController(ScenarioEngine engine) {
        this.engine = engine;
    }

    @PostMapping({"/collection/token/", "/disbursement/token/"})
    public Map<String, Object> token() {
        return Map.of(
            "access_token", SessionToken.issue(engine.tokenTtl()),
            "token_type", "access_token",
            "expires_in", (int) engine.tokenTtl().toSeconds());
    }
}
