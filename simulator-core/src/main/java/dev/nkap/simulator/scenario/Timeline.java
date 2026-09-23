package dev.nkap.simulator.scenario;

import java.util.List;

/**
 * What the engine needs from a scenario: a timeline of what happens at each interaction
 * point of one payment (ADR 0002). Each face declares its own scenario type, because the
 * words a timeline is written in — above all the statuses a query answers with and a
 * callback carries — are its operator's, not the engine's.
 *
 * <p>{@code onQuery} is never empty and its last entry is the one that repeats: query
 * <em>n</em> uses index {@code min(n - 1, size - 1)}.
 *
 * @param <Q> the face's answer to one status query
 * @param <C> the face's callback
 */
public interface Timeline<Q, C> {

    String name();

    SubmitBehaviour onSubmit();

    List<Q> onQuery();

    List<C> callbacks();
}
