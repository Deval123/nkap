package dev.nkap.simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Account Balance and Account Holder surfaces (issue #72), registered under both
 * products. Both are declared configuration, not a scenario timeline — so unlike a payment
 * there is nothing to resolve against a reference, only what {@code /_nkap/scenarios}
 * currently says.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountApiTest {

    @Autowired
    MockMvc mvc;

    @AfterEach
    void resetDeclaration() throws Exception {
        mvc.perform(delete("/_nkap/scenarios"));
    }

    @Test
    void the_default_balance_is_answered_on_both_products() throws Exception {
        mvc.perform(get("/collection/v1_0/account/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableBalance").value("1000.00"))
            .andExpect(jsonPath("$.currency").value("EUR"));
        mvc.perform(get("/disbursement/v1_0/account/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableBalance").value("1000.00"))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void the_default_holder_is_active_on_both_products() throws Exception {
        mvc.perform(get("/collection/v1_0/accountholder/msisdn/46733123453/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value(true));
        mvc.perform(get("/disbursement/v1_0/accountholder/msisdn/46733123453/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value(true));
    }

    @Test
    void a_declared_balance_and_holder_status_are_answered() throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"account":{"balance":{"availableBalance":"254.50","currency":"XAF"},
                                "holder":{"active":false}}}"""));

        mvc.perform(get("/collection/v1_0/account/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableBalance").value("254.50"))
            .andExpect(jsonPath("$.currency").value("XAF"));
        mvc.perform(get("/collection/v1_0/accountholder/msisdn/46733123453/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value(false));
    }

    @Test
    void a_declared_no_response_balance_never_answers() throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"account":{"balance":{"outcome":"NO_RESPONSE"}}}"""));

        mvc.perform(get("/collection/v1_0/account/balance"))
            .andExpect(request().asyncStarted());
    }

    @Test
    void a_declared_no_response_holder_check_never_answers() throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"account":{"holder":{"outcome":"NO_RESPONSE"}}}"""));

        mvc.perform(get("/collection/v1_0/accountholder/msisdn/46733123453/active"))
            .andExpect(request().asyncStarted());
    }

    @Test
    void resetting_scenarios_returns_the_default_account_answers() throws Exception {
        mvc.perform(post("/_nkap/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"account":{"balance":{"availableBalance":"1.00","currency":"USD"}}}"""));

        mvc.perform(delete("/_nkap/scenarios"));

        mvc.perform(get("/collection/v1_0/account/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableBalance").value("1000.00"))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }
}
