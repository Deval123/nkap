package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.scenario.TimelineEngine;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /oauth/v1/generate?grant_type=client_credentials}: HTTP Basic with a Consumer
 * Key and Secret, answering {@code 200} with {@code access_token} and {@code expires_in} —
 * <strong>observed</strong>, 2026-09-18. The credentials are not checked, as no face checks
 * them; they are recorded, unchecked, in {@link MpesaReceived}. Never itself protected.
 *
 * <p>{@code expires_in} is the declared token lifetime in seconds. Safaricom's was
 * <strong>observed</strong> as {@code 3599}; this face answers whatever lifetime the scenario
 * declares, {@code 3600} by default, so a test wanting the observed value declares
 * {@code PT59M59S}. Whether Safaricom sends it as a JSON number or a string is not recorded:
 * a number here is <strong>modelled</strong>.
 */
@RestController
class MpesaTokenController {

    private final TimelineEngine<?, ?, ?> engine;
    private final MpesaTokens tokens;
    private final MpesaReceived received;

    MpesaTokenController(MpesaScenarioEngine engine, MpesaTokens tokens, MpesaReceived received) {
        this.engine = engine;
        this.tokens = tokens;
        this.received = received;
    }

    @GetMapping("/oauth/v1/generate")
    public Map<String, Object> generate(@RequestParam(value = "grant_type", required = false) String grantType,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        received.tokenRequested(authorization);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("access_token", tokens.issue());
        answer.put("expires_in", engine.tokenTtl().toSeconds());
        return answer;
    }
}
