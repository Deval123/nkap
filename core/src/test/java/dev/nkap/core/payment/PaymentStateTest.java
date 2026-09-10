package dev.nkap.core.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

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
    @DisplayName("isUnresolved is every non-terminal state after CREATED, and only those: the states the reconciler chases")
    void isUnresolvedClassifiesEveryState() {
        assertFalse(PaymentState.CREATED.isUnresolved(),
                "CREATED is not yet the operator's, or the submission is in flight");

        for (PaymentState state : EnumSet.of(PaymentState.SUBMITTED, PaymentState.PENDING, PaymentState.UNKNOWN)) {
            assertTrue(state.isUnresolved(), state + " has left CREATED and reached no verdict");
        }

        for (PaymentState state : PaymentState.values()) {
            if (state.isTerminal()) {
                assertFalse(state.isUnresolved(), state + " is terminal — there is nothing left to resolve");
            }
        }

        // Over the whole enum, so a state added later cannot default into a bucket: it must
        // be CREATED, one of the three above, or terminal, or this fails and the author has
        // to classify it deliberately — in isUnresolved's contract and this test, and in
        // PostgresReconciliationStore.UNRESOLVED_STATES and the V4 index predicate.
        for (PaymentState state : PaymentState.values()) {
            boolean classified = state == PaymentState.CREATED
                    || state == PaymentState.SUBMITTED
                    || state == PaymentState.PENDING
                    || state == PaymentState.UNKNOWN
                    || state.isTerminal();
            assertTrue(classified, state + " is unclassified for the reconciler");
        }
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

    @Test
    @DisplayName("a submission may be acknowledged as PENDING: some operators say 'awaiting the payer' in one step")
    void submissionMayBeAcknowledgedAsPending() {
        assertTrue(PaymentState.CREATED.canTransitionTo(PaymentState.PENDING));
        assertEquals(PaymentState.PENDING, PaymentState.CREATED.transitionTo(PaymentState.PENDING));
    }
}
