package dev.nkap.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #82: the minor-unit count for every member, written here as its own table rather
 * than derived from {@link Currency} itself — so a wrong value in the enum is a visible
 * mismatch against this list, not a tautology that passes no matter what the enum says.
 *
 * <p>Every value below comes from ISO 4217's own minor-unit column, cross-checked against
 * the standard rather than assumed: XAF, XOF, RWF, UGX and GNF have none; the rest have
 * two. See {@link Currency}'s own javadoc for where "which currencies are needed" (MTN's
 * footprint) came from, a separate question from this one.
 */
class CurrencyTest {

    private static final Map<Currency, Integer> ISO_4217_MINOR_UNITS = Map.ofEntries(
            Map.entry(Currency.XAF, 0),
            Map.entry(Currency.XOF, 0),
            Map.entry(Currency.RWF, 0),
            Map.entry(Currency.UGX, 0),
            Map.entry(Currency.GNF, 0),
            Map.entry(Currency.GHS, 2),
            Map.entry(Currency.NGN, 2),
            Map.entry(Currency.ZAR, 2),
            Map.entry(Currency.ZMW, 2),
            Map.entry(Currency.LRD, 2),
            Map.entry(Currency.EUR, 2),
            Map.entry(Currency.USD, 2));

    @Test
    @DisplayName("this table covers every Currency member, or a new one would ship unchecked")
    void the_table_covers_every_member() {
        assertEquals(Set.of(Currency.values()), ISO_4217_MINOR_UNITS.keySet());
    }

    @Test
    @DisplayName("every Currency member's minorUnits() matches ISO 4217")
    void minor_units_match_iso_4217() {
        for (Currency currency : Currency.values()) {
            Integer expected = ISO_4217_MINOR_UNITS.get(currency);
            assertTrue(expected != null, currency + " is missing from the ISO 4217 table above");
            assertEquals(expected, currency.minorUnits(), currency + " minor units, per ISO 4217");
        }
    }
}
