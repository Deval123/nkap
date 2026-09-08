package dev.nkap.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The scenario mechanism, driven the way a real client drives it: entirely over
 * HTTP through the {@code /_nkap/} control plane. These are the behaviours ADR
 * 0002 promises.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioMechanismTest {

    private static final String BODY = """
        {"amount":"5000","currency":"XAF",
         "payer":{"partyIdType":"MSISDN","partyId":"237600000001"}}""";

    @Autowired
    MockMvc mvc;

    @BeforeEach
    @AfterEach
    void resetControlPlane() throws Exception {
        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());
        mvc.perform(delete("/_nkap/state")).andExpect(status().isNoContent());
    }

    private String submit() throws Exception {
        String ref = UUID.randomUUID().toString();
        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());
        return ref;
    }

    private void putRules(String rulesJson) throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON).content(rulesJson))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a scenario declared through the control plane changes the outcome of a submission")
    void control_plane_scenario_changes_the_submission_outcome() throws Exception {
        putRules("""
            {"rules":[{"match":{"msisdn":"237600000001"},
                      "scenario":{"name":"down","onSubmit":{"outcome":"SERVER_ERROR"}}}]}""");

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("flapping: onQuery of [SUCCESSFUL, FAILED] returns SUCCESSFUL then FAILED, then FAILED forever")
    void flapping_status() throws Exception {
        putRules("""
            {"rules":[{"match":{"msisdn":"237600000001"},
                      "scenario":{"name":"flapping",
                                  "onQuery":[{"status":"SUCCESSFUL"},{"status":"FAILED"}]}}]}""");
        String ref = submit();

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
                .andExpect(jsonPath("$.status").value("FAILED"));
        }
    }

    @Test
    @DisplayName("freezing: an in-flight payment keeps the behaviour resolved at submission when the rules change")
    void freezing_at_submission() throws Exception {
        putRules("""
            {"rules":[{"scenario":{"name":"fails","onQuery":[{"status":"FAILED"}]}}]}""");
        String ref = submit();

        putRules("""
            {"rules":[{"scenario":{"name":"succeeds","onQuery":[{"status":"SUCCESSFUL"}]}}]}""");

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("the first matching rule wins")
    void first_matching_rule_wins() throws Exception {
        putRules("""
            {"rules":[
              {"match":{"msisdn":"237600000001"},"scenario":{"name":"a","onQuery":[{"status":"FAILED"}]}},
              {"match":{"msisdn":"237600000001"},"scenario":{"name":"b","onQuery":[{"status":"SUCCESSFUL"}]}}
            ]}""");
        String ref = submit();

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("a rule with no matcher matches every request")
    void rule_without_matcher_matches_anything() throws Exception {
        putRules("""
            {"rules":[{"scenario":{"name":"catch-all","onQuery":[{"status":"FAILED"}]}}]}""");
        String ref = submit();

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("DELETE /_nkap/state makes a known reference 404 again")
    void deleting_state_forgets_references() throws Exception {
        String ref = submit();
        mvc.perform(get("/collection/v1_0/requesttopay/" + ref)).andExpect(status().isOk());

        mvc.perform(delete("/_nkap/state")).andExpect(status().isNoContent());

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref)).andExpect(status().isNotFound());
        mvc.perform(get("/_nkap/state/" + ref)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /_nkap/scenarios returns every payment to the happy path")
    void deleting_scenarios_restores_the_happy_path() throws Exception {
        putRules("""
            {"rules":[{"scenario":{"name":"down","onSubmit":{"outcome":"SERVER_ERROR"}}}]}""");

        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());

        String ref = submit();
        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    @DisplayName("GET /_nkap/scenarios returns the current rules with durations as ISO-8601 strings")
    void current_rules_are_readable() throws Exception {
        putRules("""
            {"rules":[{"match":{"currency":"XAF"},
                      "scenario":{"name":"slow","onSubmit":{"delay":"PT2S","outcome":"ACCEPT"},
                                  "onQuery":[{"status":"FAILED"}]}}]}""");

        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rules[0].scenario.name").value("slow"))
            .andExpect(jsonPath("$.rules[0].scenario.onSubmit.delay").value("PT2S"))
            .andExpect(jsonPath("$.rules[0].scenario.onQuery[0].status").value("FAILED"));
    }

    @Test
    @DisplayName("a malformed scenario is a 400 whose body names the offending field")
    void malformed_scenario_names_the_field() throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"rules":[{"scenario":{"onSubmit":{"outcome":"NONSENSE"}}}]}"""))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("outcome")));
    }

    @Test
    @DisplayName("NO_RESPONSE never answers — asserted against a short window, not an hour")
    void no_response_does_not_answer() throws Exception {
        putRules("""
            {"rules":[{"scenario":{"name":"silent","onSubmit":{"outcome":"NO_RESPONSE"}}}]}""");

        MvcResult result = mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(request().asyncStarted())
            .andReturn();

        assertThatThrownBy(() -> result.getAsyncResult(250))
            .isInstanceOf(IllegalStateException.class);
        assertThat(result.getResponse().isCommitted()).isFalse();
    }
}
