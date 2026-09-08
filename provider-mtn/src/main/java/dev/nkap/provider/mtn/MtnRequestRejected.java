package dev.nkap.provider.mtn;

/**
 * MTN answered a submission with {@code 400}: our request was invalid — a bad amount, a
 * currency the environment does not accept, a malformed MSISDN. This is a definitive "no
 * to this request", the opposite of {@link dev.nkap.provider.ProviderUnavailableException}
 * ("I do not know"), so it must not be mapped to {@code UNKNOWN}.
 *
 * <p>It is unchecked because {@link dev.nkap.provider.ProviderAdapter#submit} has no
 * declared channel for it: {@code submit} may only acknowledge (SUBMITTED / PENDING) or
 * signal unavailability. That gap is reported by this branch.
 */
public final class MtnRequestRejected extends RuntimeException {

    private final int httpStatus;
    private final String providerCode;

    MtnRequestRejected(int httpStatus, String providerCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.providerCode = providerCode == null ? "" : providerCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** MTN's {@code code}, or empty when the body carried none. */
    public String providerCode() {
        return providerCode;
    }
}
