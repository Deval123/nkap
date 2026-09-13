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

/**
 * The Disbursements product: {@code /disbursement/v1_0/transfer}, driven by the same
 * scenario engine as {@code requesttopay}. A transfer body names the counterparty
 * {@code payee}; everything else — the 202 with an empty body, a status GET, a 409 on a
 * reused reference, a 404 on an unknown one — is the collections behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferApiTest {

    private static final String BODY = """
        {"amount":"5000","currency":"EUR",
         "payee":{"partyIdType":"MSISDN","partyId":"237600000000"}}""";

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

        mvc.perform(post("/disbursement/v1_0/transfer")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted())
            .andExpect(content().string(""));

        mvc.perform(get("/disbursement/v1_0/transfer/" + ref))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    void a_reused_reference_is_409() throws Exception {
        String ref = UUID.randomUUID().toString();

        mvc.perform(post("/disbursement/v1_0/transfer")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());
        mvc.perform(post("/disbursement/v1_0/transfer")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESOURCE_ALREADY_EXIST"))
            .andExpect(jsonPath("$.message").value("Duplicated reference id. Creation of resource failed."));
    }

    @Test
    void a_status_get_on_an_unknown_reference_is_404() throws Exception {
        mvc.perform(get("/disbursement/v1_0/transfer/" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Requested resource was not found."));
    }

    @Test
    void a_missing_reference_header_is_400() throws Exception {
        mvc.perform(post("/disbursement/v1_0/transfer")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REFERENCE_ID"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void a_malformed_reference_header_is_400() throws Exception {
        mvc.perform(post("/disbursement/v1_0/transfer")
                .header("X-Reference-Id", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REFERENCE_ID"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void the_disbursement_token_endpoint_issues_a_token() throws Exception {
        mvc.perform(post("/disbursement/token/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andExpect(jsonPath("$.expires_in").isNumber());
    }
}
