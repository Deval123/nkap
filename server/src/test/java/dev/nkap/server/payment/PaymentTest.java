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
        PaymentIntent intent = new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
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
        assertThat(payment.providerBaseUrl()).isEmpty();
        assertThat(payment.createdAt()).isEqualTo(payment.updatedAt());
    }

    @Test
    @DisplayName("recordProviderBaseUrl records a non-blank value and ignores a blank one — issue #122")
    void provider_base_url_is_recorded_once_known() {
        Payment payment = newPayment();

        payment.recordProviderBaseUrl("http://simulator:8081");
        assertThat(payment.providerBaseUrl()).isEqualTo("http://simulator:8081");

        payment.recordProviderBaseUrl("");
        assertThat(payment.providerBaseUrl())
                .as("a blank value does not erase what is already recorded")
                .isEqualTo("http://simulator:8081");
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
    @DisplayName("a hop between non-terminal states restarts nothing: not the window, the backoff, the schedule, or the escalation")
    void a_hop_between_non_terminal_states_does_not_restart_the_schedule() {
        PaymentIntent intent = new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "hello", "note", Map.of());
        Instant createdAt = Instant.now().minus(Duration.ofHours(4));
        Instant becameUnresolved = Instant.now().minus(Duration.ofHours(3));
        Instant escalatedAt = Instant.now().minus(Duration.ofHours(1));
        Instant nextAttemptDue = Instant.now().plus(Duration.ofMinutes(30));
        // A payment the reconciler has queried seven times and already escalated; its next
        // attempt is scheduled half an hour out.
        Payment payment = Payment.rehydrate(ReferenceId.newReference(), ProviderId.of("mtn"), "merchant-1", intent,
                PaymentState.UNKNOWN, "", "", "", createdAt, createdAt, List.of(),
                7, nextAttemptDue, escalatedAt, becameUnresolved, null, 0L);

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
        assertThat(payment.escalatedAt())
                .as("a hop does not un-escalate — one episode of not knowing gets one escalation")
                .isEqualTo(escalatedAt);
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

    // --- refunds (issue #84) --------------------------------------------------------------

    @Test
    @DisplayName("a plain payment refunds nothing: refundOf is empty and the full amount remains refundable")
    void a_plain_payment_is_not_a_refund() {
        Payment payment = newPayment();

        assertThat(payment.refundOf()).isEmpty();
        assertThat(payment.refundedMinor()).isZero();
        assertThat(payment.refundableRemaining()).isEqualTo(Money.of(5000, Currency.EUR));
    }

    @Test
    @DisplayName("createRefund makes a DISBURSE payment naming the collection it refunds, never a third Capability.Operation")
    void create_refund_makes_a_disburse_payment() {
        ReferenceId original = ReferenceId.newReference();
        PaymentIntent refundIntent = new PaymentIntent(Capability.Operation.DISBURSE, Money.of(2000, Currency.EUR),
                "46733123453", "refund", "refund of " + original, Map.of());

        Payment refund = Payment.createRefund(ReferenceId.newReference(), ProviderId.of("mtn"), "merchant-1",
                refundIntent, original);

        assertThat(refund.refundOf()).contains(original);
        assertThat(refund.intent().operation()).isEqualTo(Capability.Operation.DISBURSE);
        assertThat(refund.state()).isEqualTo(PaymentState.CREATED);
    }

    @Test
    @DisplayName("reserveRefund lowers what is left to refund by the reserved amount")
    void reserve_refund_lowers_the_remaining_balance() {
        Payment payment = newPayment();

        payment.reserveRefund(Money.of(2000, Currency.EUR));

        assertThat(payment.refundedMinor()).isEqualTo(2000L);
        assertThat(payment.refundableRemaining()).isEqualTo(Money.of(3000, Currency.EUR));
    }

    @Test
    @DisplayName("reserveRefund throws rather than let the running total pass what was ever collected")
    void reserve_refund_refuses_to_exceed_the_original_amount() {
        Payment payment = newPayment();
        payment.reserveRefund(Money.of(4000, Currency.EUR));

        assertThatThrownBy(() -> payment.reserveRefund(Money.of(1001, Currency.EUR)))
                .isInstanceOf(RefundExceedsRemainingException.class);
        assertThat(payment.refundedMinor())
                .as("a refused reservation changes nothing")
                .isEqualTo(4000L);
    }

    @Test
    @DisplayName("reserveRefund rejects a non-positive amount")
    void reserve_refund_rejects_a_non_positive_amount() {
        Payment payment = newPayment();

        assertThatThrownBy(() -> payment.reserveRefund(Money.of(0, Currency.EUR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.refundedMinor()).isZero();
    }

    @Test
    @DisplayName("releaseRefundReservation gives the amount back, for a refund that ended FAILED or EXPIRED")
    void release_refund_reservation_restores_the_balance() {
        Payment payment = newPayment();
        payment.reserveRefund(Money.of(2000, Currency.EUR));

        payment.releaseRefundReservation(Money.of(2000, Currency.EUR));

        assertThat(payment.refundedMinor()).isZero();
        assertThat(payment.refundableRemaining()).isEqualTo(Money.of(5000, Currency.EUR));
    }

    @Test
    @DisplayName("a second reservation succeeds once the first is released, even though together they exceed the amount")
    void reservation_and_release_compose_across_two_refunds() {
        Payment payment = newPayment();
        payment.reserveRefund(Money.of(4000, Currency.EUR));   // refund A: in flight
        payment.releaseRefundReservation(Money.of(4000, Currency.EUR)); // refund A: FAILED, released

        payment.reserveRefund(Money.of(4000, Currency.EUR));   // refund B: now fits again

        assertThat(payment.refundedMinor()).isEqualTo(4000L);
    }
}
