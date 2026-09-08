package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * How the simulator answers the submission POST: after {@code delay}, with
 * {@code outcome}.
 *
 * <p>Both fields are optional in JSON. A missing behaviour is
 * {@link SubmitOutcome#ACCEPT} with no delay.
 */
public record SubmitBehaviour(Duration delay, SubmitOutcome outcome) {

    public SubmitBehaviour {
        delay = delay != null ? delay : Duration.ZERO;
        outcome = outcome != null ? outcome : SubmitOutcome.ACCEPT;
    }
}
