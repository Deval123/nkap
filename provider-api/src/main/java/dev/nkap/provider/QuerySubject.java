package dev.nkap.provider;

import dev.nkap.core.payment.ReferenceId;
import java.util.Objects;

/**
 * Everything the gateway holds that might identify a payment to the provider being asked
 * about it, for {@link ProviderAdapter#query}.
 *
 * <p>A record rather than a growing parameter list, deliberately: after {@code v1.0.0} every
 * change to {@link ProviderAdapter} is breaking, so a shape that can grow a field without
 * breaking every adapter's signature is worth more now than it will ever be again. That is
 * not an invitation to add a field on spec — see {@link #reference} and
 * {@link #providerReference}'s own javadoc for what earned a place here and why.
 *
 * @param reference         the reference Nkap chose and used as {@link ProviderAdapter#submit}'s
 *                          idempotency key. Always present.
 * @param providerReference the provider's own id for the request, exactly as
 *                          {@link SubmitResult.Acknowledged#providerReference()} returned it
 *                          at submission and the gateway persisted it — blank when the
 *                          provider never returned one (issue #96). For MTN this is always
 *                          blank ({@code requesttopay}'s {@code 202} carries no body), which
 *                          is the case worth testing: an operator whose status call needs a
 *                          token it issued — Orange's documented {@code transactionstatus}
 *                          appears to need {@code pay_token} — needs this field, and MTN
 *                          alone would never have revealed that the contract was missing it.
 */
public record QuerySubject(ReferenceId reference, String providerReference) {

    public QuerySubject {
        Objects.requireNonNull(reference, "reference");
        providerReference = providerReference == null ? "" : providerReference;
    }

    /** A query with no provider reference known — every case before issue #96, and most tests. */
    public static QuerySubject of(ReferenceId reference) {
        return new QuerySubject(reference, "");
    }
}
