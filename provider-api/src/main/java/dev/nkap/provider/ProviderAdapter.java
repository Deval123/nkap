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
 * <p><strong>An adapter is handed everything it needs to translate.</strong> If a method
 * would have to look something up — read gateway state, consult a payment it cannot see —
 * to do its job, the contract is missing an argument, and the fix is to add it here while
 * there is one adapter to change with it. ADR 0005 learned this from {@code submit};
 * {@link #query} learned it again from MTN's two products, and ADR 0008 records the
 * boundary.
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

    /**
     * Asks the provider what became of a reference. The authority on the outcome.
     *
     * <p>{@code capability} is the operation the reference was submitted under —
     * {@code COLLECT} or {@code DISBURSE}. An operator that runs one product per operation
     * (MTN: Collections and Disbursements have different base paths) needs it to know which
     * one to ask; an operator with a single status endpoint may ignore it. The caller has
     * it — the payment records its operation — so it is passed, not looked up. See ADR 0008.
     */
    ProviderStatus query(ReferenceId reference, Capability capability) throws ProviderUnavailableException;

    /**
     * Authenticates and interprets an incoming webhook.
     *
     * <p>This deliberately takes <strong>no capability</strong>. A webhook is the operator's
     * own message and carries whatever it carries; for MTN a callback is the same JSON for
     * both products and maps through the same table, so there is nothing product-specific to
     * route on. If a future operator sent product-distinct callbacks, that is a fact its own
     * adapter reads out of the payload — not a parameter the gateway supplies and no other
     * implementation uses. Do not add one to "complete the symmetry" with {@link #query}.
     */
    CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException;

    /**
     * The balance Nkap holds at the provider for one product, for reconciliation against the
     * float account.
     *
     * <p>{@code capability} names the product — {@code COLLECT} or {@code DISBURSE} — for the
     * same reason {@link #query} takes one: MTN holds a separate balance per product. An
     * operator with a single balance may ignore it. Carried on the contract now, though no
     * caller uses it yet, so the account-balance slice does not have to churn this signature
     * a second time for the reason {@code query} just did.
     */
    Money balance(Capability capability, Currency currency) throws ProviderUnavailableException;
}
