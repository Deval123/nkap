package dev.nkap.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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

/**
 * Token expiry mid-flight (issue #9). Enforcement is opt-in: every test here
 * turns it on explicitly, and the rest of the suite — which never does — is
 * proof that off is the default.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TokenEnforcementTest {

    private static final String BODY = """
        {"amount":"5000","currency":"XAF",
         "payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    @AfterEach
    void resetControlPlane() throws Exception {
        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());
        mvc.perform(delete("/_nkap/state")).andExpect(status().isNoContent());
    }

    private void enforce(String ttl) throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":{\"ttl\":\"" + ttl + "\",\"enforce\":true},\"rules\":[]}"))
            .andExpect(status().isNoContent());
    }

    private String freshToken() throws Exception {
        String payload = mvc.perform(post("/collection/token/"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(payload);
        return node.get("access_token").asText();
    }

    private int submitStatus(String reference, String authorization) throws Exception {
        var request = post("/collection/v1_0/requesttopay")
            .header("X-Reference-Id", reference)
            .contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int queryStatus(String reference, String authorization) throws Exception {
        var request = get("/collection/v1_0/requesttopay/" + reference);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("with enforcement off — the default — a call carrying no token succeeds")
    void off_by_default_no_token_succeeds() throws Exception {
        String ref = UUID.randomUUID().toString();

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());
        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("with enforcement on, a call carrying no token is 401 — submit and query alike")
    void enforced_no_token_is_401() throws Exception {
        enforce("PT1H");
        String ref = UUID.randomUUID().toString();

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("with enforcement on, a garbage Authorization header is 401")
    void enforced_garbage_token_is_401() throws Exception {
        enforce("PT1H");

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", UUID.randomUUID().toString())
                .header("Authorization", "Bearer not-a-real-token")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("with enforcement on, a freshly issued token is accepted")
    void enforced_fresh_token_is_accepted() throws Exception {
        enforce("PT1H");
        String token = freshToken();
        String ref = UUID.randomUUID().toString();

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());
        mvc.perform(get("/collection/v1_0/requesttopay/" + ref)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    @DisplayName("with a short lifetime, the same token turns 401 once it has elapsed, then a new one is accepted")
    void token_dies_mid_flight_then_a_new_one_works() throws Exception {
        enforce("PT3S");
        String token = freshToken();
        String ref = UUID.randomUUID().toString();

        // Alive right now: the submit goes through.
        assertThat(submitStatus(ref, "Bearer " + token)).isEqualTo(202);

        // It dies mid-flight. Wait for that rather than sleeping a fixed amount.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(queryStatus(ref, "Bearer " + token)).isEqualTo(401));

        // A newly issued token gets the client moving again — the sequence the issue is about.
        String renewed = freshToken();
        assertThat(queryStatus(ref, "Bearer " + renewed)).isEqualTo(200);
    }

    @Test
    @DisplayName("DELETE /_nkap/scenarios turns enforcement back off")
    void deleting_scenarios_turns_enforcement_off() throws Exception {
        enforce("PT1H");

        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("request-shape errors keep priority: a malformed X-Reference-Id is 400 even with enforcement on and no token")
    void malformed_reference_is_400_before_401() throws Exception {
        enforce("PT1H");

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /_nkap/scenarios reports whether enforcement is on")
    void control_plane_reports_enforce() throws Exception {
        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(jsonPath("$.token.enforce").value(false));

        enforce("PT30S");

        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(jsonPath("$.token.enforce").value(true))
            .andExpect(jsonPath("$.token.ttl").value("PT30S"));
    }

    @Test
    @DisplayName("POST /collection/token/ is never itself protected")
    void token_endpoint_is_never_protected() throws Exception {
        enforce("PT1H");

        mvc.perform(post("/collection/token/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty());
    }
}
