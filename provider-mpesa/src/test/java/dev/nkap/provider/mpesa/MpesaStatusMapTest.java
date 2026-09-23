package dev.nkap.provider.mpesa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.payment.PaymentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MpesaStatusMapTest {

    @Test
    @DisplayName("4999, observed in flight, is PENDING: a state, not an error")
    void still_processing_is_pending() {
        assertThat(MpesaStatusMap.stateFor(4999)).isEqualTo(PaymentState.PENDING);
    }

    @Test
    @DisplayName("1037 is FAILED: the operator's conclusive verdict that the payer never responded, not an absence of one")
    void no_response_from_user_is_failed() {
        assertThat(MpesaStatusMap.stateFor(1037)).isEqualTo(PaymentState.FAILED);
    }

    @Test
    @DisplayName("0 is SUCCEEDED -- modelled, never observed")
    void success_is_succeeded() {
        assertThat(MpesaStatusMap.stateFor(0)).isEqualTo(PaymentState.SUCCEEDED);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1032, 2001, 500, -1, 987654})
    @DisplayName("any other ResultCode is UNKNOWN, never FAILED")
    void anything_else_is_unknown(int code) {
        assertThat(MpesaStatusMap.stateFor(code)).isEqualTo(PaymentState.UNKNOWN);
    }
}
