package dev.nkap.provider.mtn;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.Capability;
import dev.nkap.provider.HolderStatus;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One {@link ProviderAdapter} for MTN, two products behind it: Collections and
 * Disbursements.
 *
 * <p>{@link ProviderId} names the operator, not the product, and
 * {@code ConfiguredAdapterRegistry} rightly refuses two adapters claiming one id. So MTN is
 * one adapter that declares {@code {COLLECT, DISBURSE}} and holds the untouched
 * {@link MtnCollectionsAdapter} and a {@link MtnDisbursementsAdapter}, each with its own
 * {@link MtnProfile} (its own subscription key, API user/key and token endpoint) and its own
 * token cache.
 *
 * <p>Every method routes on what it is <strong>given</strong>:
 *
 * <ul>
 *   <li>{@link #submit} on {@code intent.operation()};</li>
 *   <li>{@link #query} on the {@code capability} argument the caller passes — the operation
 *       the reference was submitted under. It used to route on a lookup the wiring injected,
 *       which read the operation off the recorded payment; the contract now carries it
 *       (ADR 0008), so the lookup and the fallback are gone.</li>
 *   <li>{@link #parseCallback} routes on nothing: an MTN callback is the same JSON for both
 *       products and both parsers map it through the same {@link MtnStatusMap}, so it
 *       delegates to one. The contract's javadoc says why this one takes no capability.</li>
 * </ul>
 */
public final class MtnAdapter implements ProviderAdapter {

    private static final ProviderId ID = ProviderId.of("mtn");

    private final MtnCollectionsAdapter collections;
    /** {@code null} when the deployment has not configured the Disbursements product. */
    private final MtnDisbursementsAdapter disbursements;

    /**
     * @param disbursements the Disbursements adapter, or {@code null} when
     *                      {@code nkap.provider.mtn.disbursement.*} is unset — then
     *                      {@link #capabilities()} does not advertise {@code DISBURSE} and a
     *                      {@code DISBURSE} call fails with a clear message.
     */
    public MtnAdapter(MtnCollectionsAdapter collections, MtnDisbursementsAdapter disbursements) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.disbursements = disbursements;
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    /**
     * The union of what each configured product declares — not a hardcoded pair. Collections
     * and Disbursements each already say which operation and which features (balance,
     * holder validation) they support; the facade does not repeat that knowledge, it just
     * adds the sets together. A product that is not configured contributes nothing, so
     * {@code capabilities()} stops naming {@code DISBURSE} when {@code disbursements} is
     * {@code null} — {@code BALANCE} and {@code HOLDER_VALIDATION} stay declared (Collections
     * still offers both), and {@link #productAdapter} is what then refuses a call asking for
     * either of those under {@code DISBURSE}, the same way it already refuses {@code query}.
     */
    @Override
    public Set<Capability> capabilities() {
        if (disbursements == null) {
            return collections.capabilities();
        }
        Set<Capability> union = new LinkedHashSet<>(collections.capabilities());
        union.addAll(disbursements.capabilities());
        return Set.copyOf(union);
    }

    @Override
    public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
        Objects.requireNonNull(intent, "intent");
        return productAdapter(intent.operation()).submit(intent, reference);
    }

    @Override
    public ProviderStatus query(ReferenceId reference, Capability.Operation capability)
            throws ProviderUnavailableException {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(capability, "capability");
        return productAdapter(capability).query(reference, capability);
    }

    @Override
    public CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException {
        return collections.parseCallback(callback);
    }

    @Override
    public Money balance(Capability.Operation capability, Currency currency) throws ProviderUnavailableException {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(currency, "currency");
        return productAdapter(capability).balance(capability, currency);
    }

    @Override
    public HolderStatus validateHolder(Capability.Operation capability, String msisdn) throws ProviderUnavailableException {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(msisdn, "msisdn");
        return productAdapter(capability).validateHolder(capability, msisdn);
    }

    /**
     * Exhaustive over {@link Capability.Operation}'s two members, so there is nothing left
     * for a {@code default} to catch — the type already refused anything else before this
     * method was called.
     */
    private ProviderAdapter productAdapter(Capability.Operation operation) {
        return switch (operation) {
            case COLLECT -> collections;
            case DISBURSE -> {
                if (disbursements == null) {
                    throw new IllegalStateException(
                            "MTN disbursements is not configured; set nkap.provider.mtn.disbursement.subscription-key, "
                                    + ".api-user and .api-key");
                }
                yield disbursements;
            }
        };
    }
}
