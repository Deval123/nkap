package dev.nkap.simulator.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the ordered rule list and the per-reference state, and answers the two
 * questions a controller needs: which scenario a submission resolves to, and
 * what the next query on a reference should return.
 *
 * <p>Two invariants from ADR 0002 live here:
 *
 * <ol>
 *   <li><strong>Resolution happens once, at submission, and is frozen against
 *       the reference.</strong> Replacing the rules afterwards does not change
 *       how an in-flight payment behaves.</li>
 *   <li><strong>The last {@code onQuery} entry repeats.</strong> Query number
 *       <em>n</em> uses index {@code min(n - 1, size - 1)}, so a client that
 *       polls past the end of the scenario keeps getting a defined answer.</li>
 * </ol>
 */
@Component
public class ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);

    /** The resolved scenario for a reference, plus how it has been queried. */
    public record ReferenceState(Scenario scenario, Instant submittedAt, int queryCount) {}

    private volatile List<ScenarioRule> rules = List.of();
    private final Map<String, ReferenceState> states = new ConcurrentHashMap<>();

    /**
     * The scenario resolved for the most recent submission, used by the token
     * endpoint. Starts as the happy path so a token can be issued before any
     * payment is submitted.
     */
    private volatile Scenario lastResolved = Scenario.happyPath();

    /**
     * Resolves the scenario for a new submission and freezes it against
     * {@code referenceId}. First matching rule wins; a matcher field that is
     * null is not compared; no match means {@link Scenario#happyPath()}.
     */
    public Scenario resolveForSubmission(String referenceId, String msisdn, String amount, String currency) {
        Scenario scenario = match(referenceId, msisdn, amount, currency);
        states.put(referenceId, new ReferenceState(scenario, Instant.now(), 0));
        lastResolved = scenario;
        log.info("reference {} resolved to scenario '{}'", referenceId, scenario.name());
        return scenario;
    }

    private Scenario match(String referenceId, String msisdn, String amount, String currency) {
        for (ScenarioRule rule : rules) {
            if (rule.match().matches(referenceId, msisdn, amount, currency)) {
                return rule.scenario();
            }
        }
        return Scenario.happyPath();
    }

    /**
     * The behaviour for the next query on {@code referenceId}, or empty if it
     * was never submitted (the caller then answers 404). Advances the query
     * count; the last {@code onQuery} entry is returned for every query past the
     * end of the list.
     */
    public Optional<QueryBehaviour> nextQueryBehaviour(String referenceId) {
        ReferenceState advanced = states.computeIfPresent(referenceId,
                (ref, state) -> new ReferenceState(state.scenario(), state.submittedAt(), state.queryCount() + 1));
        if (advanced == null) {
            return Optional.empty();
        }
        List<QueryBehaviour> onQuery = advanced.scenario().onQuery();
        int index = Math.min(advanced.queryCount() - 1, onQuery.size() - 1);
        return Optional.of(onQuery.get(index));
    }

    /** The token lifetime for the most recent submission's scenario. */
    public Duration tokenTtl() {
        TokenBehaviour token = lastResolved.token();
        return token != null ? token.ttl() : Duration.ofHours(1);
    }

    // --- control plane ---------------------------------------------------------

    /** Replaces the rule list. In-flight payments keep the scenario they resolved to. */
    public void replaceRules(List<ScenarioRule> newRules) {
        this.rules = List.copyOf(newRules);
    }

    public List<ScenarioRule> rules() {
        return rules;
    }

    /** Back to the happy path only. */
    public void resetRules() {
        this.rules = List.of();
        this.lastResolved = Scenario.happyPath();
    }

    public Optional<ReferenceState> state(String referenceId) {
        return Optional.ofNullable(states.get(referenceId));
    }

    /** Forgets every reference, so a test suite's cases do not leak into one another. */
    public void forgetAllState() {
        states.clear();
        this.lastResolved = Scenario.happyPath();
    }
}
