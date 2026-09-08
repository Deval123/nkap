package dev.nkap.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two ADR 0002 invariants the engine has to hold, exercised without a web layer. */
class ScenarioEngineTest {

    private final ScenarioEngine engine = new ScenarioEngine();

    private static Scenario onQuery(MomoStatus... statuses) {
        List<QueryBehaviour> behaviours = java.util.Arrays.stream(statuses)
            .map(s -> new QueryBehaviour(null, s, null))
            .toList();
        return new Scenario("under-test", null, behaviours, null, null);
    }

    private static ScenarioRule ruleFor(String msisdn, Scenario scenario) {
        return new ScenarioRule(new RequestMatcher(null, msisdn, null, null), scenario);
    }

    @Test
    @DisplayName("a reference never submitted has no next query behaviour")
    void unknown_reference_has_no_behaviour() {
        assertThat(engine.nextQueryBehaviour("never-seen")).isEmpty();
    }

    @Test
    @DisplayName("with no rules a submission resolves to the happy path")
    void no_rules_means_happy_path() {
        Scenario resolved = engine.resolveForSubmission("ref-1", "237600000000", "5000", "XAF");

        assertThat(resolved).isEqualTo(Scenario.happyPath());
        assertThat(engine.nextQueryBehaviour("ref-1")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.SUCCESSFUL));
    }

    @Test
    @DisplayName("the last onQuery entry repeats for every further query")
    void last_on_query_entry_repeats() {
        engine.replaceRules(List.of(ruleFor("237600000001", onQuery(MomoStatus.SUCCESSFUL, MomoStatus.FAILED))));
        engine.resolveForSubmission("ref-flap", "237600000001", "5000", "XAF");

        assertThat(engine.nextQueryBehaviour("ref-flap")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.SUCCESSFUL));
        for (int i = 0; i < 9; i++) {
            assertThat(engine.nextQueryBehaviour("ref-flap")).get()
                .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.FAILED));
        }
    }

    @Test
    @DisplayName("resolution is frozen at submission: replacing the rules does not move an in-flight payment")
    void resolution_is_frozen_at_submission() {
        engine.replaceRules(List.of(ruleFor("237600000002", onQuery(MomoStatus.FAILED))));
        engine.resolveForSubmission("ref-frozen", "237600000002", "5000", "XAF");

        engine.replaceRules(List.of(ruleFor("237600000002", onQuery(MomoStatus.SUCCESSFUL))));

        assertThat(engine.nextQueryBehaviour("ref-frozen")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.FAILED));
    }

    @Test
    @DisplayName("the first matching rule wins")
    void first_matching_rule_wins() {
        engine.replaceRules(List.of(
            ruleFor("237600000003", onQuery(MomoStatus.FAILED)),
            ruleFor("237600000003", onQuery(MomoStatus.SUCCESSFUL))));
        engine.resolveForSubmission("ref-order", "237600000003", "5000", "XAF");

        assertThat(engine.nextQueryBehaviour("ref-order")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.FAILED));
    }

    @Test
    @DisplayName("forgetting state makes a known reference unknown again")
    void forget_state_clears_references() {
        engine.resolveForSubmission("ref-gone", "237600000000", "5000", "XAF");
        assertThat(engine.state("ref-gone")).isPresent();

        engine.forgetAllState();

        assertThat(engine.state("ref-gone")).isEmpty();
        assertThat(engine.nextQueryBehaviour("ref-gone")).isEmpty();
    }

    @Test
    @DisplayName("the token lifetime comes from the most recent submission's scenario")
    void token_ttl_follows_last_submission() {
        assertThat(engine.tokenTtl()).isEqualTo(java.time.Duration.ofHours(1));

        engine.replaceRules(List.of(ruleFor("237600000004",
            new Scenario("short-token", null, null, null, new TokenBehaviour(java.time.Duration.ofSeconds(30))))));
        engine.resolveForSubmission("ref-token", "237600000004", "5000", "XAF");

        assertThat(engine.tokenTtl()).isEqualTo(java.time.Duration.ofSeconds(30));
    }
}
