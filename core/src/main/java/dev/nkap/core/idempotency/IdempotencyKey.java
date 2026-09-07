package dev.nkap.core.idempotency;

import java.util.Objects;

/**
 * A caller-supplied key, scoped to the merchant that sent it.
 *
 * <p>Scoping matters: two merchants independently choosing the key "order-1" must not
 * collide, and a merchant must not be able to probe another's traffic by guessing keys.
 */
public record IdempotencyKey(String merchantId, String key) {

    public IdempotencyKey {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(key, "key");
        if (merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters, was " + key.length());
        }
    }

    @Override
    public String toString() {
        return merchantId + "/" + key;
    }
}
