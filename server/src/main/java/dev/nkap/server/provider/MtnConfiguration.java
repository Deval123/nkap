package dev.nkap.server.provider;

import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mtn.MtnAdapter;
import dev.nkap.provider.mtn.MtnCollectionsAdapter;
import dev.nkap.provider.mtn.MtnDisbursementsAdapter;
import dev.nkap.provider.mtn.MtnProfile;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One {@link ProviderAdapter} per configured MTN installation (issue #82) — a country
 * installation <strong>is</strong> its own adapter, with its own {@link ProviderId}
 * ({@code mtn-cm}, {@code mtn-gh}, ...), not one {@code mtn} adapter routing between
 * several. See the pull request for the alternative considered (one adapter holding many
 * installations) and why it was rejected: {@code query(reference, capability)} would need
 * the country passed alongside the reference, undoing what issue #67 removed.
 *
 * <p>Both {@code @Bean} methods return a {@code List}, not one bean each, because the count
 * is data-driven — {@code nkap.provider.mtn.installations} may hold one entry or twenty.
 * {@link ConfiguredAdapterRegistry} already collects every {@link ProviderAdapter} and
 * {@link ProviderRouting} bean in the context; a bean whose own type is the list Spring
 * autowires elsewhere satisfies that collection injection directly, so nothing there needed
 * to change.
 *
 * <p>Each product is a whole {@link MtnProfile} — its own subscription key and API
 * user/key — sharing only the installation's base URL, target environment, currency and
 * country. This bean needs nothing from the payment store: since #67 the capability travels
 * on {@code query(reference, capability)}, so the facade routes on its argument.
 */
@Configuration
class MtnConfiguration {

    @Bean
    List<ProviderAdapter> mtnAdapters(MtnProperties properties) {
        return properties.installations().stream()
                .filter(MtnProperties.Installation::isConfigured)
                .map(MtnConfiguration::adapterFor)
                .toList();
    }

    /**
     * The routing for each installation: the currency it settles in, taken from
     * configuration so the gateway can turn away a request in another currency before a
     * payment exists.
     */
    @Bean
    List<ProviderRouting> mtnRoutings(MtnProperties properties) {
        return properties.installations().stream()
                .filter(MtnProperties.Installation::isConfigured)
                .map(installation -> new ProviderRouting(providerId(installation), installation.currency()))
                .toList();
    }

    private static ProviderAdapter adapterFor(MtnProperties.Installation installation) {
        ProviderId id = providerId(installation);
        MtnCollectionsAdapter collections = new MtnCollectionsAdapter(
                id, collectionProfile(installation), installation.requestTimeout());

        // Disbursements is a separate product; an installation that only collects leaves
        // disbursement.* unset, the adapter is null, and MtnAdapter neither advertises
        // DISBURSE nor lets one through.
        MtnDisbursementsAdapter disbursements = installation.disbursement().isConfigured()
                ? new MtnDisbursementsAdapter(id, disbursementProfile(installation), installation.requestTimeout())
                : null;

        return new MtnAdapter(collections, disbursements);
    }

    /**
     * {@code mtn-} plus the installation's country, e.g. {@code mtn-cm} for Cameroon.
     * Derived, not configured separately — see {@link MtnProperties.Installation}'s javadoc.
     */
    private static ProviderId providerId(MtnProperties.Installation installation) {
        return ProviderId.of("mtn-" + installation.country().strip().toLowerCase(Locale.ROOT));
    }

    private static MtnProfile collectionProfile(MtnProperties.Installation installation) {
        return new MtnProfile(
                installation.baseUrl(),
                installation.targetEnvironment(),
                installation.subscriptionKey(),
                installation.apiUser(),
                installation.apiKey(),
                installation.currency(),
                installation.country());
    }

    private static MtnProfile disbursementProfile(MtnProperties.Installation installation) {
        MtnProperties.Disbursement d = installation.disbursement();
        // MtnProfile rejects blanks, so an unconfigured disbursement block fails here at
        // startup — clearer than the first DISBURSE payment failing.
        return new MtnProfile(
                installation.baseUrl(),
                installation.targetEnvironment(),
                d.subscriptionKey(),
                d.apiUser(),
                d.apiKey(),
                installation.currency(),
                installation.country());
    }
}
