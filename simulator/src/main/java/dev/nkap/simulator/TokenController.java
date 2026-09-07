package dev.nkap.simulator;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MTN hands out a short-lived bearer token before any Collections call. The
 * simulator issues one that is syntactically plausible and always valid for an
 * hour. Token expiry mid-flight is issue #9, not part of the skeleton.
 */
@RestController
public class TokenController {

    @PostMapping("/collection/token/")
    public Map<String, Object> token() {
        return Map.of(
            "access_token", UUID.randomUUID().toString(),
            "token_type", "access_token",
            "expires_in", 3600);
    }
}
