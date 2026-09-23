package dev.nkap.simulator;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How MTN identifies a payment: by the {@code X-Reference-Id} UUID the caller sends with
 * the submission, which is also MTN's idempotency key — a second submission carrying one
 * already used is refused with {@code RESOURCE_ALREADY_EXIST}, observed against the real
 * sandbox and recorded in {@code docs/providers/mtn.md}. Case is not significant; see
 * {@link References}.
 */
@Component
class MtnPaymentIdentity implements PaymentIdentity {

    @Override
    public Minter mintedBy() {
        return Minter.CALLER;
    }

    @Override
    public Repeat onRepeat() {
        return Repeat.REFUSED;
    }

    @Override
    public String canonical(String identity) {
        return References.canonical(identity);
    }

    /** A random UUID: the shape every {@code X-Reference-Id} has, so one no client ever sent. */
    @Override
    public String neverSubmitted() {
        return UUID.randomUUID().toString();
    }
}
