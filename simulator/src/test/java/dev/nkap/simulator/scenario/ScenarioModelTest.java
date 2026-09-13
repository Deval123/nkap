package dev.nkap.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenario model is the in-memory shape and the file shape at once (ADR
 * 0002). These pin down that a scenario file may declare only what it changes.
 */
class ScenarioModelTest {

    private final ObjectMapper json = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    @DisplayName("a JSON document that omits every optional field deserialises into the happy path")
    void empty_document_is_the_happy_path() throws Exception {
        Scenario scenario = json.readValue("{}", Scenario.class);

        assertThat(scenario.name()).isEqualTo("happy-path");
        assertThat(scenario.onSubmit().outcome()).isEqualTo(SubmitOutcome.ACCEPT);
        assertThat(scenario.onSubmit().delay()).isEqualTo(Duration.ZERO);
        assertThat(scenario.onQuery()).singleElement()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.SUCCESSFUL));
        assertThat(scenario.callbacks()).isEmpty();
        assertThat(scenario).isEqualTo(Scenario.happyPath());
    }

    @Test
    @DisplayName("a fully populated scenario round-trips through Jackson unchanged")
    void full_scenario_round_trips() throws Exception {
        String document = """
            {
              "name": "the-works",
              "onSubmit": {"delay": "PT1S", "outcome": "SERVER_ERROR"},
              "onQuery": [
                {"status": "PENDING"},
                {"delay": "PT2S", "status": "FAILED", "reason": "PAYER_NOT_FOUND"}
              ],
              "callbacks": [
                {"after": "PT5S", "every": "PT3S", "times": 2, "target": "UNKNOWN_REFERENCE", "status": "SUCCESSFUL"}
              ]
            }""";

        Scenario scenario = json.readValue(document, Scenario.class);
        Scenario reparsed = json.readValue(json.writeValueAsString(scenario), Scenario.class);

        assertThat(reparsed).isEqualTo(scenario);
        assertThat(scenario.onSubmit().delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(scenario.onQuery()).hasSize(2);
        assertThat(scenario.onQuery().get(1).reason()).isEqualTo("PAYER_NOT_FOUND");
        assertThat(scenario.callbacks().get(0).times()).isEqualTo(2);
        assertThat(scenario.callbacks().get(0).after()).isEqualTo(Duration.ofSeconds(5));
        assertThat(scenario.callbacks().get(0).every()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a scenario written before onSubmit had a code still parses, and declares no code")
    void a_scenario_without_a_code_still_parses() throws Exception {
        Scenario scenario = json.readValue("""
            {"onSubmit": {"outcome": "SERVER_ERROR"}}""", Scenario.class);

        assertThat(scenario.onSubmit().outcome()).isEqualTo(SubmitOutcome.SERVER_ERROR);
        // Null, not a default: what the scenario declared, nothing more. The code the
        // operator actually answers with is chosen where the error body is built.
        assertThat(scenario.onSubmit().code()).isNull();
    }

    @Test
    @DisplayName("onSubmit.code is free text, so a scenario can name an operator code nothing recognises")
    void a_declared_code_is_not_restricted_to_a_known_vocabulary() throws Exception {
        Scenario scenario = json.readValue("""
            {"onSubmit": {"outcome": "BAD_REQUEST", "code": "UNRECOGNISED_OPERATOR_CODE"}}""",
            Scenario.class);

        assertThat(scenario.onSubmit().code()).isEqualTo("UNRECOGNISED_OPERATOR_CODE");
        assertThat(json.readValue(json.writeValueAsString(scenario), Scenario.class)).isEqualTo(scenario);
    }

    @Test
    @DisplayName("a token lifetime is a session property, not part of a scenario")
    void token_behaviour_stands_alone() throws Exception {
        assertThat(new TokenBehaviour(null).ttl()).isEqualTo(Duration.ofHours(1));

        TokenBehaviour parsed = json.readValue("{\"ttl\":\"PT2S\"}", TokenBehaviour.class);
        assertThat(parsed.ttl()).isEqualTo(Duration.ofSeconds(2));
        assertThat(json.readValue(json.writeValueAsString(parsed), TokenBehaviour.class)).isEqualTo(parsed);
    }

    @Test
    @DisplayName("durations serialise as ISO-8601 strings, not fractional seconds")
    void durations_are_iso_8601_strings() throws Exception {
        String out = json.writeValueAsString(new SubmitBehaviour(Duration.ofSeconds(2), SubmitOutcome.ACCEPT, null));

        assertThat(out).contains("\"PT2S\"").doesNotContain("2.0");
    }

    @Test
    @DisplayName("a callback with times below one is stored as one, and a missing interval is zero")
    void callback_defaults() {
        CallbackSpec spec = new CallbackSpec(null, null, 0, null, null);
        assertThat(spec.times()).isEqualTo(1);
        assertThat(spec.every()).isEqualTo(Duration.ZERO);
        assertThat(spec.after()).isEqualTo(Duration.ZERO);
        assertThat(new CallbackSpec(null, null, -3, null, null).times()).isEqualTo(1);
    }

    @Test
    @DisplayName("a matcher with every field null matches every request")
    void null_matcher_matches_anything() {
        RequestMatcher any = new RequestMatcher(null, null, null, null);

        assertThat(any.matches("ref", "237600000000", "5000", "XAF")).isTrue();
        assertThat(any.matches(null, null, null, null)).isTrue();
    }
}
