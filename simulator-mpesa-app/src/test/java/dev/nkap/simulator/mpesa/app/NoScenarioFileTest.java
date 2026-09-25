package dev.nkap.simulator.mpesa.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * not exist on this machine, the same as any container nothing was mounted into. Issue #99:
 * that is not an error, here as in the MTN simulator.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoScenarioFileTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("no file mounted still starts the simulator on the default scenario -- "
            + "no rules declared, and a submission is accepted")
    void starts_on_the_default_scenario() throws Exception {
        mvc.perform(get("/_nkap/scenarios"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rules").isEmpty());

        mvc.perform(post("/mpesa/stkpush/v1/processrequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"BusinessShortCode":174379,"Amount":"1","PhoneNumber":"254708374149",
                     "CallBackURL":"https://example.invalid/nkap/mpesa","AccountReference":"no-file"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ResponseCode").value("0"));
    }
}
