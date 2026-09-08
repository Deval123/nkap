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
}
