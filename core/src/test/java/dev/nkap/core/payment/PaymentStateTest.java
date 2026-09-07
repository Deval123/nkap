package dev.nkap.core.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant these tests defend: a provider that did not answer has not said "no".
 */
class PaymentStateTest {

    @Test
    @DisplayName("a timeout moves a payment to UNKNOWN, never to FAILED")
    void timeoutIsNotFailure() {
        assertEquals(PaymentState.UNKNOWN, PaymentState.SUBMITTED.onProviderTimeout());
        assertEquals(PaymentState.UNKNOWN, PaymentState.PENDING.onProviderTimeout());
        assertEquals(PaymentState.UNKNOWN, PaymentState.CREATED.onProviderTimeout());
    }

    @Test
    @DisplayName("UNKNOWN is not terminal: something must still resolve it")
    void unknownIsNotTerminal() {
        assertFalse(PaymentState.UNKNOWN.isTerminal());
        assertTrue(PaymentState.UNKNOWN.needsResolution());
        assertFalse(PaymentState.UNKNOWN.movesMoney());
    }

    @Test
    @DisplayName("an unknown payment can still resolve either way")
    void unknownResolvesBothWays() {
        assertEquals(PaymentState.SUCCEEDED, PaymentState.UNKNOWN.transitionTo(PaymentState.SUCCEEDED));
        assertEquals(PaymentState.FAILED, PaymentState.UNKNOWN.transitionTo(PaymentState.FAILED));
        assertEquals(PaymentState.EXPIRED, PaymentState.UNKNOWN.transitionTo(PaymentState.EXPIRED));
    }

    @Test
    @DisplayName("terminal states are final and say so")
    void terminalStatesAreFinal() {
        for (PaymentState terminal : new PaymentState[] {
                PaymentState.SUCCEEDED, PaymentState.FAILED, PaymentState.EXPIRED }) {
            assertTrue(terminal.isTerminal(), terminal + " should be terminal");
            assertTrue(terminal.allowedNext().isEmpty(), terminal + " should have no outgoing transitions");
            assertEquals(terminal, terminal.onProviderTimeout(),
                    "a late timeout must not disturb a settled payment");
        }
    }

    @Test
    @DisplayName("a settled payment cannot be reopened")
    void cannotReopenSettledPayment() {
        IllegalTransitionException thrown = assertThrows(IllegalTransitionException.class,
                () -> PaymentState.SUCCEEDED.transitionTo(PaymentState.FAILED));

        assertEquals(PaymentState.SUCCEEDED, thrown.from());
        assertEquals(PaymentState.FAILED, thrown.to());
        assertTrue(thrown.getMessage().contains("terminal"), thrown.getMessage());
    }

    @Test
    @DisplayName("only SUCCEEDED writes to the ledger")
    void onlySuccessMovesMoney() {
        for (PaymentState state : PaymentState.values()) {
            assertEquals(state == PaymentState.SUCCEEDED, state.movesMoney(), state.toString());
        }
    }

    @Test
    @DisplayName("a payment cannot skip straight from creation to success")
    void cannotSkipSubmission() {
        assertThrows(IllegalTransitionException.class,
                () -> PaymentState.CREATED.transitionTo(PaymentState.SUCCEEDED));
    }
}
