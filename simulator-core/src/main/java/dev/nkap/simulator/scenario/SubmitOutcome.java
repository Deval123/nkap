package dev.nkap.simulator.scenario;

/**
 * What the simulator does when a payment is submitted.
 *
 * <p>{@code NO_RESPONSE} is the timeout case: the request is accepted and then
 * nothing comes back. It is not a failure — a client must resolve it through a
 * later query, never by assuming the payment did not happen.
 */
public enum SubmitOutcome {
    ACCEPT,
    CONFLICT,
    BAD_REQUEST,
    SERVER_ERROR,
    NO_RESPONSE
}
