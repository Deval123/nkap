package dev.nkap.server.adjustment;

import java.util.Arrays;
import java.util.Optional;

/**
 * What the proposer believes happened, from a closed set. The codes are stored as text under
 * a {@code CHECK} constraint in V13 that lists the same four, so an unknown value is refused
 * by the database as well as here; {@code AdjustmentSchemaIT} holds the two lists together.
 */
public enum Hypothesis {

    /** The operator charged a fee that Nkap's ledger never recorded. */
    UNRECORDED_FEE("unrecorded_fee"),
    /** Money moved at the operator that no payment accounts for. */
    UNATTRIBUTED_MOVEMENT("unattributed_movement"),
    /** The float Nkap records at a provider has drifted from what the provider holds. */
    FLOAT_DRIFT("float_drift"),
    /** The same movement was posted twice. */
    DUPLICATE_POSTING("duplicate_posting");

    private final String code;

    Hypothesis(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Hypothesis> fromCode(String code) {
        return Arrays.stream(values()).filter(h -> h.code.equals(code)).findFirst();
    }
}
