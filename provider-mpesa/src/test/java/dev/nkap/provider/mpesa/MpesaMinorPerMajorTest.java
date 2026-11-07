package dev.nkap.provider.mpesa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MpesaAdapter#minorPerMajor} replaced {@code (long) Math.pow(10, minorUnits)}. The
 * reference expression is kept here, in test code, so the claim that the two agree is run
 * rather than argued.
 */
class MpesaMinorPerMajorTest {

    private static long viaFloatingPoint(int minorUnits) {
        return (long) Math.pow(10, minorUnits);
    }

    @Test
    @DisplayName("the table equals the floating-point power of ten it replaced, for every minor-unit count ISO 4217 assigns")
    void the_table_equals_the_expression_it_replaced() {
        for (int minorUnits = 0; minorUnits <= 4; minorUnits++) {
            assertThat(MpesaAdapter.minorPerMajor(minorUnits))
                    .as("%d minor units", minorUnits)
                    .isEqualTo(viaFloatingPoint(minorUnits));
        }
    }

    @Test
    @DisplayName("the table equals the old expression for every currency Nkap knows")
    void the_table_agrees_for_every_known_currency() {
        for (Currency currency : Currency.values()) {
            assertThat(MpesaAdapter.minorPerMajor(currency.minorUnits()))
                    .as(currency.name())
                    .isEqualTo(viaFloatingPoint(currency.minorUnits()));
        }
    }

    @Test
    @DisplayName("a minor-unit count outside ISO 4217's range is refused, not guessed")
    void an_unknown_count_is_refused() {
        assertThatThrownBy(() -> MpesaAdapter.minorPerMajor(5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MpesaAdapter.minorPerMajor(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
