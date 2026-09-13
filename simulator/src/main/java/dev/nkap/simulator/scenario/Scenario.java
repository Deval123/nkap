package dev.nkap.simulator.scenario;

import java.util.List;

/**
 * A scenario is a timeline: what the simulator does at each interaction point of
 * one payment. See ADR 0002 for why this shape and not a response mapping.
 *
 * <p>Every field is optional in JSON and defaults sensibly, so a scenario file
 * declares only what it changes:
 *
 * <ul>
 *   <li>no {@code onSubmit} means {@link SubmitOutcome#ACCEPT} with no delay;</li>
 *   <li>an empty {@code onQuery} means a single {@link MomoStatus#SUCCESSFUL}.</li>
 * </ul>
 *
 * <p>{@code onQuery} is never empty after construction and its last entry is the
 * one that repeats: query <em>n</em> uses index {@code min(n - 1, size - 1)}.
 *
 * <p>The token lifetime is <strong>not</strong> here: a bearer token is obtained
 * before any payment exists, so it belongs to the operator session, not to a
 * payment's timeline. It is declared alongside the rules — see
 * {@link TokenBehaviour}.
 */
public record Scenario(
        String name,
        SubmitBehaviour onSubmit,
        List<QueryBehaviour> onQuery,
        List<CallbackSpec> callbacks) {

    /** The name a scenario carries when its file does not give it one. */
    public static final String DEFAULT_NAME = "happy-path";

    public Scenario {
        name = (name == null || name.isBlank()) ? DEFAULT_NAME : name;
        onSubmit = onSubmit != null ? onSubmit : new SubmitBehaviour(null, null, null);
        onQuery = (onQuery == null || onQuery.isEmpty())
                ? List.of(new QueryBehaviour(null, null, null))
                : List.copyOf(onQuery);
        callbacks = callbacks != null ? List.copyOf(callbacks) : List.of();
    }

    /**
     * The default every other scenario deviates from: accepted on submission,
     * {@code SUCCESSFUL} on the next query. Named rather than left implicit so a
     * {@code null} scenario is always a bug.
     */
    public static Scenario happyPath() {
        return new Scenario(DEFAULT_NAME, null, null, null);
    }
}
