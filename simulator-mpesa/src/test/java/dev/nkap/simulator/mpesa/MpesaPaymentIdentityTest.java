package dev.nkap.simulator.mpesa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MpesaPaymentIdentityTest {

    private final MpesaPaymentIdentity identity =
            new MpesaPaymentIdentity(Clock.fixed(Instant.parse("2026-09-23T17:19:33Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("a minted CheckoutRequestID reads the submission's Nairobi local time, as observed three times")
    void a_minted_identity_encodes_nairobi_local_time() {
        String minted = identity.mint("254708374149");

        // ws_CO_230920262019337708374149 was minted for a submission at 17:19:34Z; this face
        // writes the same prefix for the same instant and phone number.
        assertThat(minted).startsWith("ws_CO_23092026201933").hasSize(30).endsWith("708374149");
    }

    @Test
    @DisplayName("ten submissions from one phone in one second get ten different identities")
    void ten_submissions_in_one_second_do_not_collide() {
        Set<String> minted = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            minted.add(identity.mint("254708374149"));
        }
        assertThat(minted).hasSize(10);
    }

    @Test
    @DisplayName("an identity nobody submitted has the same shape as a minted one")
    void a_never_submitted_identity_has_the_minted_shape() {
        assertThat(identity.neverSubmitted()).matches("ws_CO_23092026201933\\d{10}");
    }

    @Test
    @DisplayName("MerchantRequestID follows the quoted sample's shape and is the same every time for one CheckoutRequestID")
    void merchant_request_id_is_stable_and_shaped_like_the_sample() {
        String id = MpesaPaymentIdentity.merchantRequestId("ws_CO_230920262019337708374149");

        assertThat(id).matches("[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\d{5}");
        assertThat(MpesaPaymentIdentity.merchantRequestId("ws_CO_230920262019337708374149")).isEqualTo(id);
    }
}
