package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * The lifetime of the bearer token the simulator hands out. Missing means one
 * hour, which is what the real sandbox does.
 */
public record TokenBehaviour(Duration ttl) {

    public TokenBehaviour {
        ttl = ttl != null ? ttl : Duration.ofHours(1);
    }
}
