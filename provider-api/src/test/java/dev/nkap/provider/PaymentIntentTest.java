package dev.nkap.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import java.lang.reflect.Constructor;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What used to be a runtime check — an operation of {@code BALANCE} or {@code STATEMENT} was
 * rejected with {@code IllegalArgumentException} — is now a type (issue #70). There is no
 * test here that feeds a {@link Capability.Feature} to this constructor and expects a
 * rejection: that call does not compile, so it cannot be written. What is left to test is
 * that the type is the one doing the work.
 */
class PaymentIntentTest {

    @Test
    @DisplayName("the canonical constructor takes a Capability.Operation, not the wider Capability — a Feature cannot reach it")
    void the_operation_parameter_is_narrowed_to_capability_operation() throws NoSuchMethodException {
        // getConstructor requires an exact parameter-type match, so this call itself is the
        // proof: it throws NoSuchMethodException if the parameter is still the wider
        // Capability. The assertion below just says the same thing in a message a failing
        // build shows.
        Constructor<PaymentIntent> canonical = PaymentIntent.class.getConstructor(
                Capability.Operation.class, Money.class, String.class, String.class, String.class, Map.class);

        assertEquals(Capability.Operation.class, canonical.getParameterTypes()[0],
                "operation is Capability.Operation — BALANCE and STATEMENT are not expressible here");
    }

    @Test
    @DisplayName("a non-positive amount is still rejected — the one runtime check the constructor has left")
    void a_non_positive_amount_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentIntent(
                Capability.Operation.COLLECT, Money.zero(Currency.EUR), "46733123453", "", "", Map.of()));
    }
}
