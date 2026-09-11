package dev.nkap.provider;

/**
 * Whether the account behind an MSISDN is active at the provider — the answer to
 * {@link ProviderAdapter#validateHolder}.
 *
 * <p>Exactly two values, on purpose. There is no {@code UNKNOWN} member: "I do not know" is
 * not a value this type can hold, it is {@link ProviderUnavailableException} thrown instead
 * of a return — the same "a timeout is never a failure" rule every other read on this
 * contract follows, aimed at a person instead of a payment. An adapter that cannot
 * confidently say {@code ACTIVE} or {@code INACTIVE} has no third value to reach for, which
 * is what keeps a guess from ever reaching a merchant dressed up as an answer.
 */
public enum HolderStatus {
    ACTIVE,
    INACTIVE
}
