package dev.nkap.provider;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;

import java.util.Set;

/**
 * The whole contract a new provider has to satisfy.
 *
 * <p>Deliberately narrow and deliberately dumb. An adapter translates; it does not
 * decide. State transitions, idempotency and bookkeeping live in the core, so that
 * adding a provider cannot introduce a bug in any of them.
 *
 * <p>Every implementation must pass the conformance kit before it is merged. That is how
 * a provider nobody on the project has an account with can still be accepted.
 */
public interface ProviderAdapter {

    ProviderId id();

    Set<Capability> capabilities();

    /**
     * Hands the request to the provider using {@code reference} as the idempotency key.
     *
     * <p>The same reference must be reused on every retry of the same intent — that is
     * what makes a retry safe. Implementations must throw
     * {@link ProviderUnavailableException} rather than guess when no answer arrives.
     */
    SubmitResult submit(PaymentIntent intent, ReferenceId reference)
            throws ProviderUnavailableException;

    /** Asks the provider what became of a reference. The authority on the outcome. */
    ProviderStatus query(ReferenceId reference) throws ProviderUnavailableException;

    /** Authenticates and interprets an incoming webhook. */
    CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException;

    /** The balance Nkap holds at the provider, for reconciliation against the float account. */
    Money balance(Currency currency) throws ProviderUnavailableException;
}
