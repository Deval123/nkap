package dev.nkap.core.payment;

import java.util.Objects;
import java.util.UUID;

/**
 * The identifier Nkap generates for a payment and persists <em>before</em> calling the
 * provider.
 *
 * <p>This ordering is the whole point. If the network drops after the request leaves and
 * before the response arrives, the payment may exist at the provider; without a reference
 * written down first, nothing can ever query it and the money is lost to everyone.
 *
 * <p>At MTN this value is sent as {@code X-Reference-Id}, which the provider also treats
 * as its idempotency key. A retry therefore reuses the same reference, never a new one.
 */
public record ReferenceId(UUID value) {

    public ReferenceId {
        Objects.requireNonNull(value, "value");
    }

    public static ReferenceId newReference() {
        return new ReferenceId(UUID.randomUUID());
    }

    public static ReferenceId of(String value) {
        return new ReferenceId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
