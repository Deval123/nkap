package dev.nkap.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Issue #26: every error the operator surfaces answer with is
 * {@code {"message": …, "code": …}}, so an adapter can always read a {@code code} — never
 * Spring's own error envelope and never an empty body. The protocol errors (a reused
 * reference, an unknown one, a malformed header) are asserted where they are specified, in
 * {@link RequestToPayApiTest} and {@link TransferApiTest}; what is here is the
 * scenario-driven half, and the two outcomes that must <em>not</em> have grown a body.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MtnErrorShapeTest {

    private static final String COLLECTION_BODY = """
        {"amount":"5000","currency":"XAF",
         "payer":{"partyIdType":"MSISDN","partyId":"237600000001"}}""";

    private static final String TRANSFER_BODY = """
        {"amount":"5000","currency":"XAF",
         "payee":{"partyIdType":"MSISDN","partyId":"237600000001"}}""";

    @Autowired
    MockMvc mvc;

    @BeforeEach
    @AfterEach
    void resetControlPlane() throws Exception {
        mvc.perform(delete("/_nkap/scenarios")).andExpect(status().isNoContent());
        mvc.perform(delete("/_nkap/state")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a scenario CONFLICT is a 409 carrying RESOURCE_ALREADY_EXIST, not an empty body")
    void scenario_conflict_carries_the_duplicate_code() throws Exception {
        declare("CONFLICT", null);

        submitCollection()
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESOURCE_ALREADY_EXIST"))
            .andExpect(jsonPath("$.message").value("Duplicated reference id. Creation of resource failed."));
    }

    @Test
    @DisplayName("a scenario BAD_REQUEST is a 400 carrying an operator code, not an empty body")
    void scenario_bad_request_carries_a_code() throws Exception {
        declare("BAD_REQUEST", null);

        submitCollection()
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("NOT_ALLOWED"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("a scenario SERVER_ERROR is a 500 carrying INTERNAL_PROCESSING_ERROR, not an empty body")
    void scenario_server_error_carries_a_code() throws Exception {
        declare("SERVER_ERROR", null);

        submitCollection()
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_PROCESSING_ERROR"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("a scenario may declare the operator code it fails with, keeping the outcome's status")
    void a_scenario_may_declare_its_own_code() throws Exception {
        declare("BAD_REQUEST", "INVALID_CURRENCY");

        submitCollection()
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CURRENCY"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /**
     * The capability this issue exists for. A conformance kit has to be able to ask an
     * operator for a code <em>nothing</em> recognises, to prove an adapter maps it to
     * {@code UNKNOWN} rather than to some known failure — so the field is free text, the
     * simulator never validates it against a vocabulary, and it reaches the wire verbatim.
     */
    @ParameterizedTest(name = "{0} reports the declared code and its own status")
    @CsvSource({"CONFLICT, 409", "BAD_REQUEST, 400", "SERVER_ERROR, 500"})
    @DisplayName("a code no vocabulary contains is answered verbatim, on every failing outcome")
    void an_unrecognised_code_is_answered_verbatim(String outcome, int expectedStatus) throws Exception {
        declare(outcome, "UNRECOGNISED_OPERATOR_CODE");

        submitCollection()
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value("UNRECOGNISED_OPERATOR_CODE"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("a declared code replaces the outcome's default without changing its status")
    void a_declared_code_does_not_change_the_status() throws Exception {
        declare("CONFLICT", "SOME_ARBITRARY_OPERATOR_CODE");

        submitCollection()
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SOME_ARBITRARY_OPERATOR_CODE"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("a declared code is echoed by the control plane, and a scenario without one still parses")
    void a_declared_code_round_trips_and_stays_optional() throws Exception {
        declare("SERVER_ERROR", "SERVICE_UNAVAILABLE");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/_nkap/scenarios"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rules[0].scenario.onSubmit.code").value("SERVICE_UNAVAILABLE"));

        // The pre-#26 shape — no code at all — is still a valid scenario.
        declare("CONFLICT", null);
        submitCollection().andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the disbursements surface fails with the same shape as collections")
    void transfer_errors_have_the_same_shape() throws Exception {
        declare("BAD_REQUEST", "PAYEE_NOT_FOUND");

        mvc.perform(post("/disbursement/v1_0/transfer")
                .header("X-Reference-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PAYEE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("ACCEPT is unchanged: 202 with a genuinely empty body, error codes or not")
    void accept_still_answers_202_with_an_empty_body() throws Exception {
        declare("ACCEPT", "IGNORED_BECAUSE_ACCEPT_IS_NOT_AN_ERROR");

        submitCollection()
            .andExpect(status().isAccepted())
            .andExpect(content().string(""));
    }

    @Test
    @DisplayName("NO_RESPONSE is unchanged: still no answer at all, not an error body")
    void no_response_still_answers_nothing() throws Exception {
        declare("NO_RESPONSE", "IGNORED_BECAUSE_THERE_IS_NO_RESPONSE");

        MvcResult result = submitCollection()
            .andExpect(request().asyncStarted())
            .andReturn();

        assertThatThrownBy(() -> result.getAsyncResult(250)).isInstanceOf(IllegalStateException.class);
        assertThat(result.getResponse().isCommitted()).isFalse();
    }

    private ResultActions submitCollection() throws Exception {
        return mvc.perform(post("/collection/v1_0/requesttopay")
            .header("X-Reference-Id", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(COLLECTION_BODY));
    }

    private void declare(String outcome, String code) throws Exception {
        String onSubmit = code == null
            ? "{\"outcome\":\"%s\"}".formatted(outcome)
            : "{\"outcome\":\"%s\",\"code\":\"%s\"}".formatted(outcome, code);
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":[{\"scenario\":{\"name\":\"errors\",\"onSubmit\":%s}}]}".formatted(onSubmit)))
            .andExpect(status().isNoContent());
    }
}
