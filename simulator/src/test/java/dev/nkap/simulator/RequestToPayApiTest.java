package dev.nkap.simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RequestToPayApiTest {

    private static final String BODY = """
        {"amount":"5000","currency":"XAF",
         "payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    CollectionRequestStore store;

    @BeforeEach
    void reset() {
        store.clear();
    }

    @Test
    void accepts_with_empty_body_then_reports_successful() throws Exception {
        String ref = UUID.randomUUID().toString();

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted())
            .andExpect(content().string(""));

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    void reference_lookup_is_case_insensitive() throws Exception {
        String ref = UUID.randomUUID().toString().toUpperCase();

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    void duplicate_reference_is_conflict() throws Exception {
        String ref = UUID.randomUUID().toString();

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict());
    }

    @Test
    void missing_reference_header_is_bad_request() throws Exception {
        mvc.perform(post("/collection/v1_0/requesttopay")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformed_reference_header_is_bad_request() throws Exception {
        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknown_reference_is_not_found() throws Exception {
        mvc.perform(get("/collection/v1_0/requesttopay/" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void token_endpoint_issues_a_bearer_token() throws Exception {
        mvc.perform(post("/collection/token/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andExpect(jsonPath("$.expires_in").value(3600));
    }
}
