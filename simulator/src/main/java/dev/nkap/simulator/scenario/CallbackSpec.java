package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * One simulated callback: delivered {@code times} times starting {@code after}
 * the submission, carrying {@code status} for the {@code target} reference.
 *
 * <p>Modelled now so the scenario file format stays stable, but <strong>not
 * delivered</strong> in this version — the dispatcher arrives with issues #5 to
 * #7. See ADR 0002.
 */
public record CallbackSpec(Duration after, int times, CallbackTarget target, MomoStatus status) {

    public CallbackSpec {
        after = after != null ? after : Duration.ZERO;
        times = Math.max(times, 1);
        target = target != null ? target : CallbackTarget.SAME_REFERENCE;
        status = status != null ? status : MomoStatus.SUCCESSFUL;
    }
}
