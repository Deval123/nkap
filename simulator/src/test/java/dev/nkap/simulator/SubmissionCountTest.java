package dev.nkap.simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

/**
 * Issue #175: {@code GET /_nkap/submissions} counts the submissions the operator processed —
 * past authentication, accepted or refused — so a test can tell whether a call reached the
 * operator, and how many times.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionCountTest {

    private static final String BODY = """
        {"amount":"5000","currency":"XAF","payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}""";

    @Autowired
    MockMvc mvc;

    @BeforeEach
    @AfterEach
    void reset() throws Exception {
        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());
        mvc.perform(delete("/_nkap/state")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a fresh operator has processed no submissions")
    void a_fresh_operator_counts_nothing() throws Exception {
        expectCount(0);
    }

    @Test
    @DisplayName("an accepted submission and a scenario-refused one each count once")
    void accepted_and_refused_submissions_each_count() throws Exception {
        submit(UUID.randomUUID().toString(), null).andExpect(status().isAccepted());
        mvc.perform(post("/_nkap/scenarios").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":[{\"scenario\":{\"onSubmit\":{\"outcome\":\"BAD_REQUEST\"}}}]}"))
            .andExpect(status().isNoContent());
        submit(UUID.randomUUID().toString(), null).andExpect(status().isBadRequest());

        expectCount(2);
    }

    @Test
    @DisplayName("a repeated reference the operator refuses still counts: it is a call that reached the operator")
    void a_refused_repeat_counts() throws Exception {
        String reference = UUID.randomUUID().toString();
        submit(reference, null).andExpect(status().isAccepted());
        submit(reference, null).andExpect(status().isConflict());

        expectCount(2);
    }

    @Test
    @DisplayName("a submission refused for its credential does not count: retrying after a 401 is not a resend")
    void a_401_does_not_count() throws Exception {
        mvc.perform(post("/_nkap/scenarios").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":{\"enforce\":true}}"))
            .andExpect(status().isNoContent());

        submit(UUID.randomUUID().toString(), "Bearer not-a-live-token").andExpect(status().isUnauthorized());

        expectCount(0);
    }

    @Test
    @DisplayName("forgetting state resets the count")
    void forgetting_state_resets_the_count() throws Exception {
        submit(UUID.randomUUID().toString(), null).andExpect(status().isAccepted());
        expectCount(1);

        mvc.perform(delete("/_nkap/state")).andExpect(status().isNoContent());

        expectCount(0);
    }

    private org.springframework.test.web.servlet.ResultActions submit(String reference, String authorization)
            throws Exception {
        var request = post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", reference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    private void expectCount(int expected) throws Exception {
        mvc.perform(get("/_nkap/submissions"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"count\":" + expected + "}", true));
    }
}
