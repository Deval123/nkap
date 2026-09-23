package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * The credential the simulated operator hands out: how long it lives, and whether
 * the operator endpoints actually require it. How the credential is shaped and
 * presented is its face's authentication scheme; when it expires, and whether that
 * matters, is decided here.
 *
 * <p>This describes the simulated operator <em>session</em>, not a payment: a
 * token is requested before any payment exists. It is declared in the same
 * document as the rules (see the control plane) so that one call replaces the
 * whole configuration. {@code ttl} defaults to one hour; {@code enforce}
 * defaults to {@code false}, so by default a request's credential is ignored
 * and every existing test and {@code curl} example keeps working untouched.
 */
public record TokenBehaviour(Duration ttl, boolean enforce) {

    public TokenBehaviour {
        ttl = ttl != null ? ttl : Duration.ofHours(1);
    }

    /** A non-enforcing token with the given lifetime (or one hour when null). */
    public TokenBehaviour(Duration ttl) {
        this(ttl, false);
    }
}
