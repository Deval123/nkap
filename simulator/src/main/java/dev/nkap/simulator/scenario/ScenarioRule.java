package dev.nkap.simulator.scenario;

/**
 * Pairs a {@link RequestMatcher} with the {@link Scenario} to play when it
 * matches. Rules are consulted in order and the first match wins; a rule with no
 * matcher matches everything.
 */
public record ScenarioRule(RequestMatcher match, Scenario scenario) {

    public ScenarioRule {
        match = match != null ? match : new RequestMatcher(null, null, null, null);
        scenario = scenario != null ? scenario : Scenario.happyPath();
    }
}
