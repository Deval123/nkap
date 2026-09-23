package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * How the simulator answers one status query: after {@code delay}, with
 * {@code status} and an optional {@code reason} (MTN attaches one to a
 * {@code FAILED} result).
 *
 * <p>A scenario carries an ordered list of these, one per successive query, and
 * the last entry repeats for every further query.
 */
public record QueryBehaviour(Duration delay, MomoStatus status, String reason) {

    public QueryBehaviour {
        delay = delay != null ? delay : Duration.ZERO;
        status = status != null ? status : MomoStatus.SUCCESSFUL;
    }
}
