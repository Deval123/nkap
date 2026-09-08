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

    private void submit(String msisdn, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        String body = """
            {"amount":"5000","currency":"XAF",
             "payer":{"partyIdType":"MSISDN","partyId":"%s"}}""".formatted(msisdn);
        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(expected);
    }

    private void declare(String json) throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isNoContent());
    }

    private void expectTokenLifetime(int seconds) throws Exception {
        mvc.perform(post("/collection/token/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expires_in").value(seconds));
    }

    @Test
    @DisplayName("a scenario declared through the control plane changes the outcome of a submission")
    void control_plane_scenario_changes_the_submission_outcome() throws Exception {
        declare("""
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
        declare("""
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
        declare("""
            {"rules":[{"scenario":{"name":"fails","onQuery":[{"status":"FAILED"}]}}]}""");
        String ref = submit();

        declare("""
            {"rules":[{"scenario":{"name":"succeeds","onQuery":[{"status":"SUCCESSFUL"}]}}]}""");

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("the first matching rule wins")
    void first_matching_rule_wins() throws Exception {
        declare("""
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
        declare("""
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
        declare("""
            {"rules":[{"scenario":{"name":"down","onSubmit":{"outcome":"SERVER_ERROR"}}}]}""");

        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());

        String ref = submit();
        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    @DisplayName("GET /_nkap/scenarios returns the declared token and rules, durations as ISO-8601 strings")
    void current_declaration_is_readable() throws Exception {
        declare("""
            {"token":{"ttl":"PT2S"},
             "rules":[{"match":{"currency":"XAF"},
                      "scenario":{"name":"slow","onSubmit":{"delay":"PT2S","outcome":"ACCEPT"},
                                  "onQuery":[{"status":"FAILED"}]}}]}""");

        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token.ttl").value("PT2S"))
            .andExpect(jsonPath("$.rules[0].scenario.name").value("slow"))
            .andExpect(jsonPath("$.rules[0].scenario.onSubmit.delay").value("PT2S"))
            .andExpect(jsonPath("$.rules[0].scenario.onQuery[0].status").value("FAILED"));
    }

    @Test
    @DisplayName("GET /_nkap/scenarios reports a one-hour token when none was declared")
    void default_token_is_one_hour() throws Exception {
        declare("""
            {"rules":[{"scenario":{"name":"x","onQuery":[{"status":"FAILED"}]}}]}""");

        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(jsonPath("$.token.ttl").value("PT1H"));
    }

    @Test
    @DisplayName("the fallback callback URL is declared with the rules and read back")
    void callback_url_round_trips_through_the_control_plane() throws Exception {
        declare("""
            {"callbackUrl":"http://localhost:9999/hook","rules":[]}""");

        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(jsonPath("$.callbackUrl").value("http://localhost:9999/hook"));

        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());

        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(jsonPath("$.callbackUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GET /_nkap/callbacks is an empty list for a reference with no attempts")
    void callbacks_endpoint_is_empty_when_nothing_was_sent() throws Exception {
        mvc.perform(get("/_nkap/callbacks/" + UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("the token lifetime is the one declared with the rule set")
    void token_lifetime_is_declared_with_the_rules() throws Exception {
        expectTokenLifetime(3600);

        declare("{\"token\":{\"ttl\":\"PT2S\"},\"rules\":[]}");

        expectTokenLifetime(2);
    }

    @Test
    @DisplayName("two submissions resolving different scenarios do not change the token lifetime")
    void submissions_do_not_disturb_the_token_lifetime() throws Exception {
        declare("""
            {"token":{"ttl":"PT2S"},
             "rules":[
               {"match":{"msisdn":"237600000001"},"scenario":{"name":"fails","onQuery":[{"status":"FAILED"}]}},
               {"match":{"msisdn":"237600000002"},"scenario":{"name":"down","onSubmit":{"outcome":"SERVER_ERROR"}}}
             ]}""");

        submit("237600000001", status().isAccepted());
        submit("237600000002", status().isInternalServerError());

        expectTokenLifetime(2);
    }

    @Test
    @DisplayName("DELETE /_nkap/scenarios returns the token lifetime to one hour")
    void deleting_scenarios_restores_the_default_token() throws Exception {
        declare("{\"token\":{\"ttl\":\"PT2S\"},\"rules\":[]}");

        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());

        expectTokenLifetime(3600);
    }

    @Test
    @DisplayName("DELETE /_nkap/state leaves a declared token lifetime alone")
    void deleting_state_keeps_the_declared_token() throws Exception {
        declare("{\"token\":{\"ttl\":\"PT2S\"},\"rules\":[]}");

        mvc.perform(delete("/_nkap/state")).andExpect(status().isNoContent());

        expectTokenLifetime(2);
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
    @DisplayName("issue #3: a submit that never answers, then a query on the same reference returns SUCCESSFUL")
    void timeout_then_late_success() throws Exception {
        declare("""
            {"rules":[{"scenario":{"name":"timeout-then-late-success",
                                   "onSubmit":{"outcome":"NO_RESPONSE"},
                                   "onQuery":[{"status":"SUCCESSFUL"}]}}]}""");

        String ref = UUID.randomUUID().toString();

        // The client submits and gets nothing back within its timeout window.
        MvcResult pending = mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(request().asyncStarted())
            .andReturn();
        assertThatThrownBy(() -> pending.getAsyncResult(250)).isInstanceOf(IllegalStateException.class);

        // It abandons the submit call and queries the reference instead.
        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    @DisplayName("NO_RESPONSE never answers — asserted against a short window, not an hour")
    void no_response_does_not_answer() throws Exception {
        declare("""
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
