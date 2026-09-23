package dev.nkap.simulator.scenario;

import java.time.Duration;

/**
 * One simulated callback, as far as delivery is concerned: delivered {@code times} times,
 * the first {@code after} the submission and each subsequent one {@code every} later, for
 * the {@code target} payment. {@code status} is what the callback reports, in the face's
 * own vocabulary; delivery carries it without reading it.
 *
 * @param <S> the face's status
 */
public interface CallbackStep<S> {

    Duration after();

    Duration every();

    int times();

    CallbackTarget target();

    S status();
}
