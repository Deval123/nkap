package dev.nkap.server.provider;

import dev.nkap.provider.ProviderId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the gateway when {@code nkap.provider.default} names a provider this deployment
 * built no adapter for, while it did build at least one.
 *
 * <p>{@code GET /balance} and {@code GET /account-holders/{msisdn}} route to that provider, and
 * resolve it per request. Without this check a default naming no adapter surfaced as a {@code 500}
 * to whoever called those routes, not at startup. The realistic way in is not a typo: the
 * property defaults to {@code mtn-cm}, which exists only when MTN Cameroon is configured, so a
 * deployment serving only M-Pesa Kenya started with a default pointing at nothing.
 *
 * <p>An <em>ill-formed</em> value was never silent: {@link ProviderId} refuses it, and both
 * controllers build theirs at construction. This check covers only the well-formed id that names
 * no adapter.
 *
 * <p>With <em>no</em> adapter configured it passes. There is then nothing for the default to
 * contradict, and a first install with no credentials yet is legitimate: the Helm chart's own
 * first install depends on starting that way.
 *
 * <p>A constructor check, so it fails during bean initialization. That is before the web server's
 * connector accepts connections, which happens only once refresh has finished.
 */
@Component
class DefaultProviderCheck {

    DefaultProviderCheck(AdapterRegistry adapters, @Value("${nkap.provider.default}") String defaultProvider) {
        ProviderId requested = ProviderId.of(defaultProvider);
        if (!adapters.configuredProviders().isEmpty() && !adapters.configuredProviders().contains(requested)) {
            throw new IllegalStateException("Refusing to start: nkap.provider.default (NKAP_PROVIDER_DEFAULT) is '"
                    + requested + "', but this deployment configures no adapter by that id. It configures "
                    + adapters.configuredProviders() + ". GET /balance and GET /account-holders route to the"
                    + " default and would fail on every call. Set it to one of the configured providers.");
        }
    }
}
