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
    @DisplayName("an inconclusive reason arriving without a status is still UNKNOWN")
    void an_inconclusive_reason_alone_is_still_unknown() {
        assertThat(MtnStatusMap.stateFor("", "SERVICE_UNAVAILABLE", "")).isEqualTo(UNKNOWN);
        assertThat(MtnStatusMap.stateFor(null, "INTERNAL_PROCESSING_ERROR", null)).isEqualTo(UNKNOWN);
        // errorCode is the last resort, consulted only when status and reason are both absent;
        // unaffected by this fix.
        assertThat(MtnStatusMap.stateFor(null, null, "SERVICE_UNAVAILABLE")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("RESOURCE_NOT_FOUND on a query is UNKNOWN, not a failure")
    void resource_not_found_is_unknown() {
        assertThat(MtnStatusMap.stateFor(null, null, "RESOURCE_NOT_FOUND")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("the settled outcomes map to their states")
    void settled_outcomes() {
        assertThat(MtnStatusMap.stateFor("SUCCESSFUL", "", "")).isEqualTo(SUCCEEDED);
        assertThat(MtnStatusMap.stateFor("PENDING", "", "")).isEqualTo(PENDING);
        assertThat(MtnStatusMap.stateFor("FAILED", "", "")).isEqualTo(FAILED);
        assertThat(MtnStatusMap.stateFor("EXPIRED", "", "")).isEqualTo(EXPIRED);
    }

    @Test
    @DisplayName("a status a real MTN account has sent but this table does not map is still UNKNOWN")
    void an_unmapped_real_status_is_unknown() {
        // CREATED: observed against the real sandbox for MTN's own "Pending" test MSISDN
        // (docs/providers/mtn.md, "Still unknown"). Not yet a row in the table; falls here.
        assertThat(MtnStatusMap.stateFor("CREATED", "", "")).isEqualTo(UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PAYER_NOT_FOUND", "PAYEE_NOT_FOUND", "NOT_ENOUGH_FUNDS", "PAYER_LIMIT_REACHED",
            "APPROVAL_REJECTED", "INVALID_CURRENCY", "NOT_ALLOWED", "INVALID_CALLBACK_URL_HOST"})
    @DisplayName("a FAILED status and an answered-refusal reason agree, and stay FAILED")
    void answered_refusals_are_failed(String reason) {
        assertThat(MtnStatusMap.stateFor("FAILED", reason, "")).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("a FAILED status refined by an EXPIRED reason stays EXPIRED — "
            + "the row a status-first fix would have broken")
    void a_more_specific_reason_refines_a_less_specific_status() {
        assertThat(MtnStatusMap.stateFor("FAILED", "EXPIRED", "")).isEqualTo(EXPIRED);
    }

    @Test
    @DisplayName("a terminal status is no longer discarded by an inconclusive reason — issue #115")
    void a_terminal_status_survives_an_inconclusive_reason() {
        // Observed against the real sandbox: 46733123450 answers status: FAILED,
        // reason: INTERNAL_PROCESSING_ERROR, conclusively, every time. This is the row the
        // issue exists for: today's map discards it and returns UNKNOWN.
        assertThat(MtnStatusMap.stateFor("FAILED", "INTERNAL_PROCESSING_ERROR", "")).isEqualTo(FAILED);

        // Never observed paired with a status. Inferred by the same reasoning as the row
        // above: SERVICE_UNAVAILABLE alone means "the operator's own system broke", which is
        // exactly as inconclusive as INTERNAL_PROCESSING_ERROR alone, and no more entitled to
        // override a status MTN did supply.
        assertThat(MtnStatusMap.stateFor("FAILED", "SERVICE_UNAVAILABLE", "")).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("two conclusive readings that contradict each other are UNKNOWN, never a guess")
    void contradictory_terminal_readings_are_unknown() {
        // Never observed. The case where guessing wrong writes a ledger entry for money that
        // may not have moved, so it is tested even though no operator has ever produced it.
        assertThat(MtnStatusMap.stateFor("SUCCESSFUL", "APPROVAL_REJECTED", "")).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("PENDING is not conclusive, so it does not survive an inconclusive reason")
    void pending_does_not_survive_an_inconclusive_reason() {
        // PENDING alone, no reason at all: still PENDING.
        assertThat(MtnStatusMap.stateFor("PENDING", "", "")).isEqualTo(PENDING);
        // PENDING paired with the operator's own system failing: unlike FAILED, a merely
        // provisional PENDING is not trustworthy enough to stand on its own here.
        assertThat(MtnStatusMap.stateFor("PENDING", "SERVICE_UNAVAILABLE", "")).isEqualTo(UNKNOWN);
    }
}
