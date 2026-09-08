package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * One simulated callback: delivered {@code times} times, the first
 * {@code after} the submission and each subsequent one {@code every} later,
 * carrying {@code status} for the {@code target} reference.
 *
 * <p>{@code times} with {@code every} reads as the requirement of issue #5 does:
 * "the same callback twice, a configurable interval apart".
 */
public record CallbackSpec(Duration after, Duration every, int times, CallbackTarget target, MomoStatus status) {

    public CallbackSpec {
        after = after != null ? after : Duration.ZERO;
        every = every != null ? every : Duration.ZERO;
        times = Math.max(times, 1);
        target = target != null ? target : CallbackTarget.SAME_REFERENCE;
        status = status != null ? status : MomoStatus.SUCCESSFUL;
    }
}
