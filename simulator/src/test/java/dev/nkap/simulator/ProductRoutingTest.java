package dev.nkap.simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #69: a reference belongs to one product. At a real operator, Collections and
 * Disbursements are different products; a reference submitted to one does not exist under
 * the other. Before this landed, the simulator kept one reference space across both, so
 * {@link #a_collections_reference_is_unknown_on_disbursements()} — the test that matters
 * first, per the issue — failed against it: a fresh reference submitted on
 * {@code /collection/v1_0/requesttopay} answered {@code 200 SUCCESSFUL} on
 * {@code GET /disbursement/v1_0/transfer/{ref}} instead of {@code 404}. Confirmed by running
 * this exact test against the code before this change (stashing the fix, not merely reading
 * it) before writing the fix at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductRoutingTest {

    private static final String COLLECTION_BODY = """
        {"amount":"5000","currency":"XAF",
         "payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}""";
    private static final String DISBURSEMENT_BODY = """
        {"amount":"5000","currency":"XAF",
         "payee":{"partyIdType":"MSISDN","partyId":"237600000000"}}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    ReferenceStore store;

    @BeforeEach
    void reset() {
        store.clear();
    }

    private void submitCollection(String ref) throws Exception {
        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(COLLECTION_BODY))
            .andExpect(status().isAccepted());
    }

    private void submitDisbursement(String ref) throws Exception {
        mvc.perform(post("/disbursement/v1_0/transfer")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(DISBURSEMENT_BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("issue #69, the test that matters first: a reference submitted on collections is unknown when queried on disbursements")
    void a_collections_reference_is_unknown_on_disbursements() throws Exception {
        String ref = UUID.randomUUID().toString();
        submitCollection(ref);

        mvc.perform(get("/disbursement/v1_0/transfer/" + ref))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("the mirror: a reference submitted on disbursements is unknown when queried on collections")
    void a_disbursements_reference_is_unknown_on_collections() throws Exception {
        String ref = UUID.randomUUID().toString();
        submitDisbursement(ref);

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("both products still answer their own references exactly as before")
    void both_products_still_answer_their_own_references() throws Exception {
        String collectionRef = UUID.randomUUID().toString();
        String disbursementRef = UUID.randomUUID().toString();
        submitCollection(collectionRef);
        submitDisbursement(disbursementRef);

        mvc.perform(get("/collection/v1_0/requesttopay/" + collectionRef))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
        mvc.perform(get("/disbursement/v1_0/transfer/" + disbursementRef))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    @DisplayName("a reused reference within one product is still 409, exactly as before")
    void a_reused_reference_within_one_product_is_still_conflict() throws Exception {
        String ref = UUID.randomUUID().toString();
        submitCollection(ref);

        mvc.perform(post("/collection/v1_0/requesttopay")
                .header("X-Reference-Id", ref)
                .contentType(MediaType.APPLICATION_JSON).content(COLLECTION_BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESOURCE_ALREADY_EXIST"));
    }

    @Test
    @DisplayName("the same reference submitted to both products conflicts with neither -- a reference belongs to one product, not one process")
    void the_same_reference_does_not_conflict_across_products() throws Exception {
        String ref = UUID.randomUUID().toString();

        submitCollection(ref);
        submitDisbursement(ref);

        mvc.perform(get("/collection/v1_0/requesttopay/" + ref))
            .andExpect(status().isOk());
        mvc.perform(get("/disbursement/v1_0/transfer/" + ref))
            .andExpect(status().isOk());
    }

    // --- the control-plane decision: a reference-keyed route answers only when exactly one
    // product holds the reference (issue #69's own open question) -------------------------

    @Test
    @DisplayName("GET /_nkap/state/{ref} answers when exactly one product holds the reference")
    void state_answers_for_the_one_product_that_holds_it() throws Exception {
        String ref = UUID.randomUUID().toString();
        submitCollection(ref);

        mvc.perform(get("/_nkap/state/" + ref))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scenario").value("happy-path"));
    }

    @Test
    @DisplayName("GET /_nkap/state/{ref} is 404 for a reference neither product has seen, unchanged from before issue #69")
    void state_is_not_found_for_neither_product() throws Exception {
        mvc.perform(get("/_nkap/state/" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /_nkap/state/{ref} is 404, not a guess, when both products hold the same reference")
    void state_is_not_found_when_both_products_hold_the_reference() throws Exception {
        String ref = UUID.randomUUID().toString();
        submitCollection(ref);
        submitDisbursement(ref);

        mvc.perform(get("/_nkap/state/" + ref))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /_nkap/callbacks/{ref} is still an empty list, not 404, for a reference neither product has seen -- unchanged: not yet is not never")
    void callbacks_still_empty_list_for_neither_product() throws Exception {
        mvc.perform(get("/_nkap/callbacks/" + UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /_nkap/callbacks/{ref} is 404, not a silently mixed answer, when both products hold the same reference")
    void callbacks_is_not_found_when_both_products_hold_the_reference() throws Exception {
        String ref = UUID.randomUUID().toString();
        submitCollection(ref);
        submitDisbursement(ref);

        mvc.perform(get("/_nkap/callbacks/" + ref))
            .andExpect(status().isNotFound());
    }
}
