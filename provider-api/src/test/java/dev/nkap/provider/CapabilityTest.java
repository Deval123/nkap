package dev.nkap.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CapabilityTest {

    @Test
    @DisplayName("every Capability is an Operation or a Feature — the compiler enforces it in a switch")
    void the_hierarchy_is_sealed_to_two_kinds() {
        // This method does not assert a type exists; it is the exhaustive switch a third
        // kind would have to fit into. Add one and this file stops compiling. Add a member
        // to Operation or Feature and this switch needs no change at all — that is the
        // property issue #70 wanted: a fifth capability cannot compile without choosing
        // which kind it is, but choosing costs nothing here or anywhere else that matches on
        // kind rather than on member.
        Capability capability = Capability.Operation.COLLECT;
        String kind = switch (capability) {
            case Capability.Operation operation -> "operation: " + operation;
            case Capability.Feature feature -> "feature: " + feature;
        };
        assertEquals("operation: COLLECT", kind);
    }

    @Test
    @DisplayName("operations() filters capabilities() by type, so a Feature never appears no matter what it is named")
    void operations_filters_capabilities_by_type_not_by_name() {
        // A fake adapter that declares one operation and both features. Nothing in
        // ProviderAdapter.operations() names COLLECT, BALANCE or STATEMENT — it filters by
        // instanceof Capability.Operation — so this proves the filtering, not a hardcoded list.
        ProviderAdapter mixed = new ProviderAdapter() {
            @Override
            public ProviderId id() {
                return ProviderId.of("test");
            }

            @Override
            public Set<Capability> capabilities() {
                return Set.of(Capability.Operation.COLLECT, Capability.Feature.BALANCE, Capability.Feature.STATEMENT);
            }

            @Override
            public SubmitResult submit(PaymentIntent intent, ReferenceId reference) {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public ProviderStatus query(ReferenceId reference, Capability.Operation capability) {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public CallbackEvent parseCallback(RawCallback callback) {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public Money balance(Capability.Operation capability, Currency currency) {
                throw new UnsupportedOperationException("not needed for this test");
            }
        };

        assertEquals(Set.of(Capability.Operation.COLLECT), mixed.operations());
    }
}
