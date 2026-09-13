package dev.nkap.core.money;

/**
 * The currencies Nkap knows how to count in.
 *
 * <p>The number of minor units is not decoration. The CFA franc has none: there is no
 * such thing as a centime of XAF, and code that assumes two decimal places everywhere
 * will silently multiply West and Central African amounts by a hundred. Getting one of
 * these wrong is not a rounding error — it is a factor of a hundred on every payment in
 * that country, and a merchant finds it before a test does.
 *
 * <p>The rule for membership is one rule, with no exceptions: a member is a currency this
 * gateway can count in, whose ISO 4217 minor-unit count has been verified — cross-checked
 * against the ISO 4217 minor-unit column (as mirrored in the community-maintained
 * {@code datasets/currency-codes} table, itself sourced from the standard) rather than
 * assumed from habit or from how the amount is usually written. Which countries a given
 * adapter actually configures is a narrower, separate question, answered by that adapter's
 * own documentation — see {@code docs/providers/mtn.md} for MTN's — and it does not decide
 * this list: a member here is not a claim that any adapter settles in it today.
 *
 * <p><strong>Membership only grows.</strong> A member is a currency amounts may already
 * exist in; removing one is not a documentation change, it is a compile error for anyone
 * who ever settled in it and a deserialisation failure for rows already in
 * {@code ledger_entry}. Adding a member is cheap — verify its minor units and add it — so
 * that is the bar for adding one; removing one is not, and the bar for that is "it should
 * never have been added," not "nothing configured today happens to use it."
 */
public enum Currency {

    // --- zero minor units (ISO 4217) ---------------------------------------------------

    /** CFA franc BEAC — Cameroon, Republic of Congo, and the rest of the CEMAC zone. */
    XAF(0),
    /** CFA franc BCEAO — Benin, Côte d'Ivoire, Guinea-Bissau, and the rest of the UEMOA zone. */
    XOF(0),
    /** Rwandan franc — Rwanda. */
    RWF(0),
    /** Ugandan shilling — Uganda. */
    UGX(0),
    /** Guinean franc — Guinea. */
    GNF(0),

    // --- two minor units (ISO 4217) ------------------------------------------------------

    /** Kenyan shilling — Kenya. */
    KES(2),
    /** Ghanaian cedi — Ghana. */
    GHS(2),
    /** Nigerian naira — Nigeria. */
    NGN(2),
    /** South African rand — South Africa. */
    ZAR(2),
    /** Zambian kwacha — Zambia. */
    ZMW(2),
    /** Liberian dollar — Liberia. */
    LRD(2),
    /** Euro — used by the simulator/demo profile and by tests that need a generic,
     *  unremarkable two-decimal currency. */
    EUR(2),
    /** United States dollar — same reason as {@link #EUR}: a generic reference currency. */
    USD(2);

    private final int minorUnits;

    Currency(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    /** Digits after the decimal separator, per ISO 4217; 0 for XAF, XOF, RWF, UGX and GNF. */
    public int minorUnits() {
        return minorUnits;
    }
}
