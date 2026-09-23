package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.scenario.CallbackStep;
import dev.nkap.simulator.scenario.CallbackTarget;
import java.time.Duration;

/**
 * One simulated STK callback: delivered {@code times} times, the first {@code after} the
 * submission and each subsequent one {@code every} later, reporting {@code status} for the
 * {@code target} payment.
 *
 * <p>Safaricom was <strong>observed</strong> to deliver an STK callback once and not again,
 * whether the receiver refused it or was not there (2026-09-22); {@code times} greater than
 * one plays something the real operator was not seen to do, and is there for a test that
 * wants it.
 */
public record MpesaCallbackSpec(Duration after, Duration every, int times, CallbackTarget target, MpesaResult status)
        implements CallbackStep<MpesaResult> {

    public MpesaCallbackSpec {
        after = after != null ? after : Duration.ZERO;
        every = every != null ? every : Duration.ZERO;
        times = Math.max(times, 1);
        target = target != null ? target : CallbackTarget.SAME_REFERENCE;
        status = status != null ? status : MpesaResult.SUCCESS;
    }
}
