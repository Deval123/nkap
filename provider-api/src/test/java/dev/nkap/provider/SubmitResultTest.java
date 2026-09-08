package dev.nkap.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nkap.core.payment.PaymentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SubmitResultTest {

    @Test
    @DisplayName("an acknowledgement may report SUBMITTED or PENDING")
    void an_acknowledgement_may_be_submitted_or_pending() {
        assertEquals(PaymentState.SUBMITTED,
                new SubmitResult.Acknowledged(PaymentState.SUBMITTED, "ref", "{}").state());
        assertEquals(PaymentState.PENDING,
                new SubmitResult.Acknowledged(PaymentState.PENDING, "ref", "{}").state());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"SUBMITTED", "PENDING"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("an acknowledgement in any other state is rejected: a submission is never terminal or unknown")
    void an_acknowledgement_in_a_definitive_state_is_rejected(PaymentState state) {
        assertThrows(IllegalArgumentException.class,
                () -> new SubmitResult.Acknowledged(state, "ref", "{}"));
    }

    @Test
    @DisplayName("the acknowledged() factory yields an Acknowledged in SUBMITTED, so old call sites read the same")
    void the_factory_is_an_acknowledged_in_submitted() {
        SubmitResult result = SubmitResult.acknowledged("ref", "{}");

        SubmitResult.Acknowledged acknowledged = assertInstanceOf(SubmitResult.Acknowledged.class, result);
        assertEquals(PaymentState.SUBMITTED, acknowledged.state());
        assertEquals("ref", acknowledged.providerReference());
    }

    @Test
    @DisplayName("a rejection carries the operator's code and reason, and normalises nulls to empty strings")
    void a_rejection_normalises_nulls() {
        SubmitResult.Rejected rejected = new SubmitResult.Rejected("INVALID_CURRENCY", "Currency not supported", null);
        assertEquals("INVALID_CURRENCY", rejected.providerCode());
        assertEquals("Currency not supported", rejected.reason());
        assertEquals("", rejected.rawResponse());

        SubmitResult.Rejected blank = new SubmitResult.Rejected(null, null, null);
        assertEquals("", blank.providerCode());
        assertEquals("", blank.reason());
        assertEquals("", blank.rawResponse());
    }

    @Test
    @DisplayName("a null acknowledgement state is rejected")
    void a_null_state_is_rejected() {
        assertThrows(NullPointerException.class,
                () -> new SubmitResult.Acknowledged(null, "ref", "{}"));
    }

    @Test
    @DisplayName("every SubmitResult is Acknowledged or Rejected — the compiler enforces it in a switch")
    void the_hierarchy_is_sealed_to_two_cases() {
        // This method does not assert a type exists; it is the exhaustive switch the plan
        // relies on — remove a branch and this file stops compiling.
        SubmitResult result = SubmitResult.acknowledged("ref", "{}");
        String describe = switch (result) {
            case SubmitResult.Acknowledged a -> "acknowledged in " + a.state();
            case SubmitResult.Rejected r -> "rejected: " + r.providerCode();
        };
        assertEquals("acknowledged in SUBMITTED", describe);
    }
}
