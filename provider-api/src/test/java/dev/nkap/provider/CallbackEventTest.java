package dev.nkap.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nkap.core.payment.ReferenceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallbackEventTest {

    private static final ProviderStatus A_STATUS = ProviderStatus.unknown("PENDING", "{}");

    @Test
    @DisplayName("the two-argument shape every adapter written against 1.0.0 uses still compiles and works unchanged")
    void the_1_0_0_shape_still_works() {
        ReferenceId reference = ReferenceId.newReference();

        CallbackEvent event = new CallbackEvent(reference, A_STATUS);

        assertEquals(reference, event.reference());
        assertEquals("", event.providerReference());
        assertEquals(A_STATUS, event.status());
    }

    @Test
    @DisplayName("the two-argument shape still rejects a null reference, exactly as it always did")
    void the_1_0_0_shape_still_rejects_a_null_reference() {
        assertThrows(NullPointerException.class, () -> new CallbackEvent((ReferenceId) null, A_STATUS));
    }

    @Test
    @DisplayName("unattributed() carries only the operator's own reference, for an operator that never echoes ours")
    void unattributed_carries_only_a_provider_reference() {
        CallbackEvent event = CallbackEvent.unattributed("ws_CO_180920261803512708374149", A_STATUS);

        assertNull(event.reference());
        assertEquals("ws_CO_180920261803512708374149", event.providerReference());
    }

    @Test
    @DisplayName("a CallbackEvent with neither a reference nor a provider reference is rejected")
    void neither_identity_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> CallbackEvent.unattributed("", A_STATUS));
        assertThrows(IllegalArgumentException.class, () -> CallbackEvent.unattributed(null, A_STATUS));
        assertThrows(IllegalArgumentException.class,
                () -> new CallbackEvent(null, null, A_STATUS));
    }

    @Test
    @DisplayName("a null status is rejected whichever constructor is used")
    void a_null_status_is_rejected() {
        ReferenceId reference = ReferenceId.newReference();
        assertThrows(NullPointerException.class, () -> new CallbackEvent(reference, null));
        assertThrows(NullPointerException.class, () -> new CallbackEvent(reference, "pr", null));
        assertThrows(NullPointerException.class, () -> CallbackEvent.unattributed("pr", null));
    }

    @Test
    @DisplayName("a null provider reference on the three-argument shape normalises to blank, not to a NullPointerException")
    void a_null_provider_reference_normalises_to_blank() {
        ReferenceId reference = ReferenceId.newReference();

        CallbackEvent event = new CallbackEvent(reference, null, A_STATUS);

        assertEquals("", event.providerReference());
    }

    @Test
    @DisplayName("both a reference and a provider reference may be present at once")
    void both_identities_may_be_present() {
        ReferenceId reference = ReferenceId.newReference();

        CallbackEvent event = new CallbackEvent(reference, "op-ref", A_STATUS);

        assertEquals(reference, event.reference());
        assertEquals("op-ref", event.providerReference());
    }
}
