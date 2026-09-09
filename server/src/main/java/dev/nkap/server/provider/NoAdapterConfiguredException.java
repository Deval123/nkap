package dev.nkap.server.provider;

import dev.nkap.provider.ProviderId;
import java.util.Set;

/**
 * A request routed to a provider the server has no adapter for. That is a server
 * misconfiguration, not a client error.
 *
 * <p>Its own type, so {@link dev.nkap.server.web.ApiExceptionHandler} can map exactly this
 * and nothing else: a stray {@code Optional.get()} elsewhere in the request path must not
 * be reported to the caller as "the provider is not configured". The message lists what is
 * configured for the log; the client is told only the type and a plain sentence.
 */
public final class NoAdapterConfiguredException extends RuntimeException {

    NoAdapterConfiguredException(ProviderId requested, Set<ProviderId> configured) {
        super("no adapter is configured for provider " + requested + "; configured: " + configured);
    }
}
