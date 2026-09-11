package dev.nkap.provider.mtn;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.util.EnumSet;
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

    @Override
    public Set<Capability> capabilities() {
        return disbursements == null
                ? EnumSet.of(Capability.COLLECT)
                : EnumSet.of(Capability.COLLECT, Capability.DISBURSE);
    }

    @Override
    public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
        Objects.requireNonNull(intent, "intent");
        return productAdapter(intent.operation()).submit(intent, reference);
    }

    @Override
    public ProviderStatus query(ReferenceId reference, Capability capability) throws ProviderUnavailableException {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(capability, "capability");
        return productAdapter(capability).query(reference, capability);
    }

    @Override
    public CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException {
        return collections.parseCallback(callback);
    }

    @Override
    public Money balance(Capability capability, Currency currency) throws ProviderUnavailableException {
        throw new UnsupportedOperationException(
                "balance is out of scope for MTN; capabilities() does not advertise BALANCE");
    }

    private ProviderAdapter productAdapter(Capability operation) {
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
            default -> throw new IllegalArgumentException(operation + " is not a payment operation MTN serves");
        };
    }
}
