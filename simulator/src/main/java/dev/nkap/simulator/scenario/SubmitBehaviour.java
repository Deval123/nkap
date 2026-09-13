package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * How the simulator answers the submission POST: after {@code delay}, with
 * {@code outcome}, and — when the outcome is a failure — reporting {@code code}
 * as the operator's error code.
 *
 * <p>All three fields are optional in JSON. A missing behaviour is
 * {@link SubmitOutcome#ACCEPT} with no delay. A missing {@code code} leaves the
 * failing outcome to answer with its default code, so every scenario written
 * before codes existed keeps working unchanged; declaring one is how a test asks
 * for a specific operator code rather than only a status. It is ignored by
 * {@link SubmitOutcome#ACCEPT} and {@link SubmitOutcome#NO_RESPONSE}, which have
 * no error body.
 *
 * <p>The codes themselves are not listed here: the vocabulary is MTN's, and it
 * lives with the error model that answers with it.
 */
public record SubmitBehaviour(Duration delay, SubmitOutcome outcome, String code) {

    public SubmitBehaviour {
        delay = delay != null ? delay : Duration.ZERO;
        outcome = outcome != null ? outcome : SubmitOutcome.ACCEPT;
    }
}
