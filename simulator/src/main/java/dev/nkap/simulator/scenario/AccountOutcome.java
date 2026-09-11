package dev.nkap.simulator.scenario;

/**
 * Whether the simulator answers a balance or account-holder read, or goes silent the way
 * {@link SubmitOutcome#NO_RESPONSE} does for a submission.
 */
public enum AccountOutcome {
    ANSWER,
    NO_RESPONSE
}
