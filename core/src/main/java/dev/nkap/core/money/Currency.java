package dev.nkap.core.money;

/**
 * The currencies Nkap knows how to count in.
 *
 * <p>The number of minor units is not decoration. The CFA franc has none: there is no
 * such thing as a centime of XAF, and code that assumes two decimal places everywhere
 * will silently multiply West and Central African amounts by a hundred.
 */
public enum Currency {

    XAF(0), // CFA franc BEAC — Cameroon, Gabon, Chad, CAR, Congo, Equatorial Guinea
    XOF(0), // CFA franc BCEAO — Senegal, Côte d'Ivoire, Mali, Benin, Burkina, Niger, Togo, Guinea-Bissau
    RWF(0),
    UGX(0),
    KES(2),
    GHS(2),
    NGN(2),
    ZAR(2),
    EUR(2),
    USD(2);

    private final int minorUnits;

    Currency(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    /** Digits after the decimal separator; 0 for XAF and XOF. */
    public int minorUnits() {
        return minorUnits;
    }
}
