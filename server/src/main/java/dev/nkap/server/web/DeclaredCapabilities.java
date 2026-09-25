package dev.nkap.server.web;

import dev.nkap.provider.Capability;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.provider.AdapterRegistry;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * The refusal {@code PaymentService.submit} makes for a {@link Capability.Operation},
 * carried across to the live reads that ask for a {@link Capability.Feature}: what an adapter
 * declares is the gateway's own routing fact, known before any call is made, so a request it
 * does not declare is refused here and the adapter is never asked (ADR 0013, change 1).
 *
 * <p>Asking anyway is not a safer way to find out. The conformance kit requires an adapter
 * to refuse what it does not declare, and it does — by throwing, which is the contract being
 * honoured and would reach the caller as an untyped {@code 500}.
 */
final class DeclaredCapabilities {

    private DeclaredCapabilities() {
    }

    /**
     * The adapter for {@code provider}, once it declares both {@code feature} and
     * {@code operation}. The feature is checked first: when the provider cannot do what
     * {@code route} does at all, which operation the caller named is beside the point.
     */
    static ProviderAdapter require(AdapterRegistry adapters, ProviderId provider, String route,
                                   Capability.Feature feature, Capability.Operation operation) {
        ProviderAdapter adapter = adapters.require(provider);
        if (!adapter.capabilities().contains(feature)) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, ProblemTypes.FEATURE_NOT_OFFERED,
                    "This deployment does not offer this",
                    route + " needs " + feature + ", and this deployment's default provider, " + provider
                            + ", declares " + names(adapter.capabilities()) + ". The operator was not asked.");
        }
        if (!adapter.operations().contains(operation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.OPERATION_NOT_SERVED,
                    "The default provider does not serve this operation",
                    "This request named " + operation + ", and this deployment's default provider, " + provider
                            + ", serves " + names(adapter.operations()) + ". The operator was not asked.");
        }
        return adapter;
    }

    /** Sorted, so the same deployment always answers in the same words. */
    private static List<String> names(Set<? extends Capability> capabilities) {
        return capabilities.stream().map(Object::toString).sorted().toList();
    }
}
