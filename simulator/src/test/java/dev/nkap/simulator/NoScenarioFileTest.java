package dev.nkap.simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * No {@code nkap.scenario.file} override here, so the simulator starts against
 * {@code application.yml}'s own default ({@code /etc/nkap/scenario.json}) -- a path that does
 * not exist on this machine, the same as any deployment nothing was mounted into. Issue #99:
 * that is not an error.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoScenarioFileTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("no file mounted still starts the simulator on the default scenario -- "
            + "no rules declared, and a submission resolves to the happy path")
    void starts_on_the_default_scenario() throws Exception {
        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rules").isEmpty());

        String ref = UUID.randomUUID().toString();
        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":"5000","currency":"XAF",
                     "payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}"""))
            .andExpect(status().isAccepted())
            .andExpect(content().string(""));

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }
}
