package dev.nkap.core.ledger;

import dev.nkap.core.money.Currency;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The address of an account in the ledger, as colon-separated segments ending in a currency.
 *
 * <p>Examples: {@code provider:mtn:float:XAF}, {@code merchant:acme:payable:XAF},
 * {@code fees:platform:XAF}, {@code suspense:mtn:XAF}.
 *
 * <p>Accounts are not created; they exist as soon as something is posted to them, and
 * their balance is the sum of those postings.
 */
public record AccountId(String value) {

    private static final Pattern SHAPE =
            Pattern.compile("^[a-z][a-z0-9-]*(:[A-Za-z0-9_.-]+)+$");

    public AccountId {
        Objects.requireNonNull(value, "value");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Malformed account id '" + value + "': expected colon-separated segments, e.g. provider:mtn:float:XAF");
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    /** Funds Nkap holds at a provider — an asset. */
    public static AccountId providerFloat(String providerId, Currency currency) {
        return new AccountId("provider:" + providerId + ":float:" + currency);
    }

    /** What Nkap owes a merchant — a liability. */
    public static AccountId merchantPayable(String merchantId, Currency currency) {
        return new AccountId("merchant:" + merchantId + ":payable:" + currency);
    }

    public static AccountId fees(String bucket, Currency currency) {
        return new AccountId("fees:" + bucket + ":" + currency);
    }

    /**
     * Where a movement goes when the provider confirms money moved but Nkap cannot yet
     * attribute it. The balance of a suspense account is a health metric: if it does not
     * return to zero, something is broken and a human needs to look.
     */
    public static AccountId suspense(String providerId, Currency currency) {
        return new AccountId("suspense:" + providerId + ":" + currency);
    }

    @Override
    public String toString() {
        return value;
    }
}
