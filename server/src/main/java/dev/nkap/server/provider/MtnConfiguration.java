package dev.nkap.server.provider;

import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.mtn.MtnCollectionsAdapter;
import dev.nkap.provider.mtn.MtnProfile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one adapter this slice configures: MTN Collections. */
@Configuration
class MtnConfiguration {

    @Bean
    ProviderAdapter mtnCollectionsAdapter(MtnProperties properties) {
        MtnProfile profile = new MtnProfile(
                properties.baseUrl(),
                properties.targetEnvironment(),
                properties.subscriptionKey(),
                properties.apiUser(),
                properties.apiKey(),
                properties.currency(),
                properties.country());
        return new MtnCollectionsAdapter(profile, properties.requestTimeout());
    }

    /**
     * The routing for this MTN installation: the currency it settles in, taken from
     * configuration so the gateway can turn away a request in another currency before a
     * payment exists.
     */
    @Bean
    ProviderRouting mtnRouting(ProviderAdapter mtnCollectionsAdapter, MtnProperties properties) {
        return new ProviderRouting(mtnCollectionsAdapter.id(), properties.currency());
    }
}
