package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.provider.ProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * MTN Cameroon's slot in the committed {@code application.yml}, reached the way a deployment
 * reaches it: through its {@code NKAP_PROVIDER_MTN_CM_*} variables, not the property names
 * behind them. Its country defaults to blank like every other slot's; setting it is what
 * configures Cameroon, exactly as before.
 */
class MtnCameroonSlotTest {

    @EnableConfigurationProperties({MtnProperties.class, MpesaProperties.class})
    static class Properties {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Properties.class, PublicBaseUrl.class, MtnConfiguration.class,
                    MpesaConfiguration.class, ConfiguredAdapterRegistry.class);

    @Test
    @DisplayName("NKAP_PROVIDER_MTN_CM_COUNTRY=cm, with its credentials, still configures mtn-cm")
    void setting_the_country_configures_cameroon() {
        runner.withPropertyValues(
                        "NKAP_PROVIDER_MTN_CM_COUNTRY=cm",
                        "NKAP_PROVIDER_MTN_CM_BASE_URL=http://localhost:1",
                        "NKAP_PROVIDER_MTN_CM_TARGET_ENVIRONMENT=sandbox",
                        "NKAP_PROVIDER_MTN_CM_SUBSCRIPTION_KEY=subscription-key",
                        "NKAP_PROVIDER_MTN_CM_API_USER=api-user",
                        "NKAP_PROVIDER_MTN_CM_API_KEY=api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AdapterRegistry registry = context.getBean(AdapterRegistry.class);
                    assertThat(registry.configuredProviders()).containsExactly(ProviderId.of("mtn-cm"));
                    assertThat(registry.providerForCountry("cm")).contains(ProviderId.of("mtn-cm"));
                });
    }

    @Test
    @DisplayName("Cameroon's credentials without its country configure nothing: the slot is off until the country says otherwise")
    void credentials_without_the_country_configure_nothing() {
        runner.withPropertyValues(
                        "NKAP_PROVIDER_MTN_CM_BASE_URL=http://localhost:1",
                        "NKAP_PROVIDER_MTN_CM_TARGET_ENVIRONMENT=sandbox",
                        "NKAP_PROVIDER_MTN_CM_SUBSCRIPTION_KEY=subscription-key",
                        "NKAP_PROVIDER_MTN_CM_API_USER=api-user",
                        "NKAP_PROVIDER_MTN_CM_API_KEY=api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AdapterRegistry.class).configuredProviders()).isEmpty();
                });
    }
}
