package dev.nkap.provider.mtn;

import static dev.nkap.core.payment.PaymentState.EXPIRED;
import static dev.nkap.core.payment.PaymentState.FAILED;
import static dev.nkap.core.payment.PaymentState.PENDING;
import static dev.nkap.core.payment.PaymentState.SUCCEEDED;
import static dev.nkap.core.payment.PaymentState.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MtnStatusMapTest {

    @Test
    @DisplayName("every code documented in ADR 0004 has an explicit entry")
    void the_table_covers_every_documented_code() {
        for (String code : MtnStatusMap.DOCUMENTED_CODES) {
            assertThat(MtnStatusMap.isKnown(code)).as("table has an entry for %s", code).isTrue();
        }
    }

    @Test
    @DisplayName("a code the table does not list maps to UNKNOWN, never to FAILED")
    void an_unrecognised_code_is_unknown() {
        assertThat(MtnStatusMap.stateFor("PENGUIN", "", "")).isEqualTo(UNKNOWN);
        assertThat(MtnStatusMap.stateFor("FAILED", "SOME_CODE_ADDED_NEXT_YEAR", "")).isEqualTo(UNKNOWN);
        assertThat(MtnStatusMap.stateFor("", "", "TOTALLY_NEW_ERROR")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("the operator's own system failing is UNKNOWN even when wrapped in a FAILED")
    void the_operators_own_failure_is_unknown() {
        assertThat(MtnStatusMap.stateFor("FAILED", "SERVICE_UNAVAILABLE", "")).isEqualTo(UNKNOWN);
        assertThat(MtnStatusMap.stateFor("FAILED", "INTERNAL_PROCESSING_ERROR", "")).isEqualTo(UNKNOWN);
        assertThat(MtnStatusMap.stateFor(null, null, "SERVICE_UNAVAILABLE")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("RESOURCE_NOT_FOUND on a query is UNKNOWN, not a failure")
    void resource_not_found_is_unknown() {
        assertThat(MtnStatusMap.stateFor(null, null, "RESOURCE_NOT_FOUND")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("the three settled outcomes map to their states")
    void settled_outcomes() {
        assertThat(MtnStatusMap.stateFor("SUCCESSFUL", "", "")).isEqualTo(SUCCEEDED);
        assertThat(MtnStatusMap.stateFor("PENDING", "", "")).isEqualTo(PENDING);
        assertThat(MtnStatusMap.stateFor("FAILED", "", "")).isEqualTo(FAILED);
        assertThat(MtnStatusMap.stateFor("EXPIRED", "", "")).isEqualTo(EXPIRED);
        assertThat(MtnStatusMap.stateFor("FAILED", "EXPIRED", "")).isEqualTo(EXPIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PAYER_NOT_FOUND", "PAYEE_NOT_FOUND", "NOT_ENOUGH_FUNDS", "PAYER_LIMIT_REACHED",
            "APPROVAL_REJECTED", "INVALID_CURRENCY", "NOT_ALLOWED", "INVALID_CALLBACK_URL_HOST"})
    @DisplayName("an answered refusal is FAILED")
    void answered_refusals_are_failed(String reason) {
        assertThat(MtnStatusMap.stateFor("FAILED", reason, "")).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("reason is consulted before status, so a FAILED SERVICE_UNAVAILABLE is UNKNOWN")
    void reason_wins_over_status() {
        assertThat(MtnStatusMap.stateFor("FAILED", "SERVICE_UNAVAILABLE", "")).isEqualTo(UNKNOWN);
        assertThat(MtnStatusMap.stateFor("FAILED", "PAYER_NOT_FOUND", "")).isEqualTo(FAILED);
    }
}
