package dev.nkap.simulator.mpesa;

import java.time.Duration;

/**
 * How the simulator answers one STK Push status query: after {@code delay}, with
 * {@code status} — or, when {@code error500} is set, with {@code HTTP 500} and
 * {@code errorCode 500.001.1001} instead, whatever the payment's state.
 *
 * <p>{@code error500} is what makes the intermittent {@code 500} declarable. It was
 * <strong>observed</strong> on 2026-09-23 answering four polls in nineteen for a reference
 * that unquestionably existed, interleaved with {@code HTTP 200} answers to the identical
 * request. The simulator never does it on its own: a test asks for it, poll by poll, so that
 * no test using this simulator is flaky by accident.
 *
 * <p>A scenario carries an ordered list of these, one per successive query, and the last
 * entry repeats for every further query — a failed poll counts as a query like any other.
 */
public record MpesaQueryBehaviour(Duration delay, MpesaResult status, boolean error500) {

    public MpesaQueryBehaviour {
        delay = delay != null ? delay : Duration.ZERO;
        status = status != null ? status : MpesaResult.SUCCESS;
    }
}
