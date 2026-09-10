package dev.nkap.server.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.IllegalTransitionException;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static Payment newPayment() {
        PaymentIntent intent = new PaymentIntent(Capability.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "hello", "note", Map.of());
        return Payment.create(ReferenceId.newReference(), ProviderId.of("mtn"), "merchant-1", intent);
    }

    @Test
    @DisplayName("a new payment is born in CREATED with no transition history")
    void a_new_payment_is_created_with_empty_history() {
        Payment payment = newPayment();

        assertThat(payment.state()).isEqualTo(PaymentState.CREATED);
        assertThat(payment.history()).isEmpty();
        assertThat(payment.providerReference()).isEmpty();
        assertThat(payment.createdAt()).isEqualTo(payment.updatedAt());
    }

    @Test
    @DisplayName("a transition moves the state and records from, to, cause and why")
    void a_transition_is_recorded_attributably() {
        Payment payment = newPayment();

        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "202 accepted");

        assertThat(payment.state()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(payment.updatedAt()).isAfterOrEqualTo(payment.createdAt());
        assertThat(payment.history()).singleElement().satisfies(transition -> {
            assertThat(transition.from()).isEqualTo(PaymentState.CREATED);
            assertThat(transition.to()).isEqualTo(PaymentState.SUBMITTED);
            assertThat(transition.cause()).isEqualTo(PaymentTransition.Cause.SUBMIT_RESPONSE);
            assertThat(transition.rawResponse()).isEqualTo("202 accepted");
            assertThat(transition.at()).isNotNull();
        });
    }

    @Test
    @DisplayName("an outright refusal records the operator's code and reason on the CREATED -> FAILED transition")
    void a_refusal_records_the_operator_code() {
        Payment payment = newPayment();

        payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.SUBMIT_RESPONSE,
                "INVALID_CURRENCY", "Currency not supported", "{\"code\":\"INVALID_CURRENCY\"}");

        assertThat(payment.state()).isEqualTo(PaymentState.FAILED);
        assertThat(payment.history()).singleElement().satisfies(transition -> {
            assertThat(transition.operatorCode()).isEqualTo("INVALID_CURRENCY");
            assertThat(transition.note()).isEqualTo("Currency not supported");
        });
    }

    @Test
    @DisplayName("an illegal transition throws rather than being silently applied")
    void an_illegal_transition_throws() {
        Payment payment = newPayment();
        payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");

        assertThatThrownBy(() -> payment.applyTransition(PaymentState.SUCCEEDED,
                PaymentTransition.Cause.CALLBACK, "", "", ""))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(payment.state()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    @DisplayName("a provider that did not answer moves the payment to UNKNOWN, never to FAILED")
    void provider_silence_is_unknown() {
        Payment payment = newPayment();

        payment.applyTransition(payment.state().onProviderTimeout(), PaymentTransition.Cause.SUBMIT_RESPONSE,
                "", "read timed out", "");

        assertThat(payment.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(payment.state().needsResolution()).isTrue();
    }

    @Test
    @DisplayName("entering an unresolved state arms the reconciler: due now, no attempts yet, not escalated, window starts now")
    void entering_an_unresolved_state_arms_the_reconciler_schedule() {
        Payment payment = newPayment();

        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");

        assertThat(payment.reconcileAttempts()).isZero();
        assertThat(payment.reconcileDueAt()).isEqualTo(payment.updatedAt());
        assertThat(payment.escalatedAt()).isNull();
        assertThat(payment.unresolvedSince()).isEqualTo(payment.updatedAt());
    }

    @Test
    @DisplayName("a hop between non-terminal states restarts nothing: not the window, not the backoff, not the schedule")
    void a_hop_between_non_terminal_states_does_not_restart_the_schedule() {
        PaymentIntent intent = new PaymentIntent(Capability.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "hello", "note", Map.of());
        Instant createdAt = Instant.now().minus(Duration.ofHours(4));
        Instant becameUnresolved = Instant.now().minus(Duration.ofHours(3));
        Instant escalatedAt = Instant.now().minus(Duration.ofHours(1));
        Instant nextAttemptDue = Instant.now().plus(Duration.ofMinutes(30));
        // A payment the reconciler has queried seven times and already escalated; its next
        // attempt is scheduled half an hour out.
        Payment payment = Payment.rehydrate(ReferenceId.newReference(), ProviderId.of("mtn"), "merchant-1", intent,
                PaymentState.UNKNOWN, "", "", createdAt, createdAt, List.of(),
                7, nextAttemptDue, escalatedAt, becameUnresolved);

        payment.applyTransition(PaymentState.PENDING, PaymentTransition.Cause.RECONCILER, "PENDING", "", "");

        assertThat(payment.unresolvedSince())
                .as("the window still runs from the first unresolved moment")
                .isEqualTo(becameUnresolved);
        assertThat(payment.reconcileAttempts())
                .as("the backoff keeps climbing across a hop — it is not pinned back to base")
                .isEqualTo(7);
        assertThat(payment.reconcileDueAt())
                .as("the schedule the last claim wrote stands — a hop does not move the due time back to now")
                .isEqualTo(nextAttemptDue);
        assertThat(payment.escalatedAt()).as("a payment that has started moving again is chased once more").isNull();
    }

    @Test
    @DisplayName("a payment that goes straight from CREATED to a terminal state never arms the reconciler")
    void a_terminal_first_transition_does_not_arm_the_reconciler() {
        Payment payment = newPayment();

        payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.SUBMIT_RESPONSE, "NOT_ALLOWED", "", "");

        assertThat(payment.unresolvedSince()).isNull();
        assertThat(payment.reconcileDueAt()).isNull();
    }

    @Test
    @DisplayName("history() is a copy: mutating the returned list does not change the payment")
    void history_is_a_defensive_copy() {
        Payment payment = newPayment();
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");

        assertThatThrownBy(() -> payment.history().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(payment.history()).hasSize(1);
    }
}
