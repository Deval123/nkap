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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * One {@link ProviderAdapter} for MTN, two products behind it: Collections and
 * Disbursements.
 *
 * <p>{@link ProviderId} names the operator, not the product, and
 * {@code ConfiguredAdapterRegistry} rightly refuses two adapters claiming one id. So MTN is
 * one adapter that declares {@code {COLLECT, DISBURSE}} and holds the untouched
 * {@link MtnCollectionsAdapter} and a new {@link MtnDisbursementsAdapter}, each with its own
 * {@link MtnProfile} (its own subscription key, API user/key and token endpoint) and its own
 * token cache.
 *
 * <p>{@link #submit} dispatches on {@code intent.operation()} — the intent says which
 * product. {@link #query} and {@link #parseCallback} are handed only a reference, so:
 *
 * <ul>
 *   <li><strong>{@code parseCallback}</strong> delegates to one product's parser. An MTN
 *       callback is the same JSON either way — {@code referenceId}, {@code status},
 *       {@code reason}, {@code financialTransactionId} — and both parsers map it through the
 *       same {@link MtnStatusMap}. There is nothing product-specific to decide.</li>
 *   <li><strong>{@code query}</strong> does need the product, to hit the right base path.
 *       It is resolved with {@code productOf} — a lookup the wiring supplies, reading the
 *       operation off the payment the gateway already recorded. The payment knows; the
 *       adapter does not see payments, which is why the lookup is injected rather than a
 *       field. {@code query} is only ever called after the caller has found the payment
 *       (see {@code SettlementService.confirm}), so the lookup resolves in practice; the
 *       {@code COLLECT} fallback is for the impossible gap.</li>
 * </ul>
 *
 * <p>The cleaner long-term shape is {@code ProviderAdapter.query(ReferenceId, Capability)} —
 * the caller has the capability and would just pass it — but that is a {@code provider-api}
 * contract change with its own issue, not something to fold into this slice. See the pull
 * request for #62.
 */
public final class MtnAdapter implements ProviderAdapter {

    private static final ProviderId ID = ProviderId.of("mtn");

    private final MtnCollectionsAdapter collections;
    /** {@code null} when the deployment has not configured the Disbursements product. */
    private final MtnDisbursementsAdapter disbursements;
    private final Function<ReferenceId, Optional<Capability>> productOf;

    /**
     * @param disbursements the Disbursements adapter, or {@code null} when
     *                      {@code nkap.provider.mtn.disbursement.*} is unset — then
     *                      {@link #capabilities()} does not advertise {@code DISBURSE} and a
     *                      {@code DISBURSE} call fails with a clear message.
     */
    public MtnAdapter(MtnCollectionsAdapter collections, MtnDisbursementsAdapter disbursements,
                      Function<ReferenceId, Optional<Capability>> productOf) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.disbursements = disbursements;
        this.productOf = Objects.requireNonNull(productOf, "productOf");
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
    public ProviderStatus query(ReferenceId reference) throws ProviderUnavailableException {
        Objects.requireNonNull(reference, "reference");
        Capability product = productOf.apply(reference).orElse(Capability.COLLECT);
        return productAdapter(product).query(reference);
    }

    @Override
    public CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException {
        return collections.parseCallback(callback);
    }

    @Override
    public Money balance(Currency currency) throws ProviderUnavailableException {
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
