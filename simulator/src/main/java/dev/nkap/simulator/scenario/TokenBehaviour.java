package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * The lifetime of the bearer token the simulator hands out.
 *
 * <p>This describes the simulated operator <em>session</em>, not a payment: a
 * token is requested before any payment exists. It is declared in the same
 * document as the rules (see the control plane) so that one call replaces the
 * whole configuration, and defaults to one hour — what the real sandbox does.
 */
public record TokenBehaviour(Duration ttl) {

    public TokenBehaviour {
        ttl = ttl != null ? ttl : Duration.ofHours(1);
    }
}
