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
 * <p><strong>Two separate questions, two separate sources — neither is "it looked
 * right."</strong> Which currencies are needed comes from MTN's own footprint: every
 * member below is the national currency of a country MTN Group operates a network in
 * (MTN Group, "Our footprint" — see {@code docs/providers/mtn.md} for which of these this
 * adapter actually configures versus merely lists as possible). How many minor units each
 * one has comes from ISO 4217 itself, not from memory or from how the amount is usually
 * written — cross-checked against the ISO 4217 minor-unit column (as mirrored in the
 * community-maintained {@code datasets/currency-codes} table, itself sourced from the
 * standard) rather than assumed from habit. {@code KES} was removed for this reason: MTN
 * does not operate in Kenya (that market is Safaricom's M-Pesa), so a currency this
 * adapter will never settle in had no basis for being here.
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
    /** Not an MTN settlement currency; kept for the simulator/demo profile and for tests
     *  that need a generic, unremarkable two-decimal currency. */
    EUR(2),
    /** Same reason as {@link #EUR}: a generic reference currency, not an MTN one. */
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
