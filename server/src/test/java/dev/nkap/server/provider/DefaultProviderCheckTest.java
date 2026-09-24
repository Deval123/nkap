package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link DefaultProviderCheck} against the committed {@code application.yml}, reached through the
 * environment variable names a deployment sets, so a default nobody sets really is the file's own.
 */
class DefaultProviderCheckTest {

    @EnableConfigurationProperties({MtnProperties.class, MpesaProperties.class})
    static class Properties {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Properties.class, PublicBaseUrl.class, MtnConfiguration.class,
                    MpesaConfiguration.class, ConfiguredAdapterRegistry.class, DefaultProviderCheck.class);

    private static final String[] MTN_CAMEROON = {
            "NKAP_PROVIDER_MTN_CM_COUNTRY=cm",
            "NKAP_PROVIDER_MTN_CM_BASE_URL=http://localhost:1",
            "NKAP_PROVIDER_MTN_CM_TARGET_ENVIRONMENT=sandbox",
            "NKAP_PROVIDER_MTN_CM_SUBSCRIPTION_KEY=subscription-key",
            "NKAP_PROVIDER_MTN_CM_API_USER=api-user",
            "NKAP_PROVIDER_MTN_CM_API_KEY=api-key"};

    private static final String[] MPESA_KENYA = {
            "NKAP_PROVIDER_MPESA_KE_COUNTRY=ke",
            "NKAP_PROVIDER_MPESA_KE_BASE_URL=http://localhost:1",
            "NKAP_PROVIDER_MPESA_KE_BUSINESS_SHORT_CODE=174379",
            "NKAP_PROVIDER_MPESA_KE_PASSKEY=passkey",
            "NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY=consumer-key",
            "NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET=consumer-secret",
            "NKAP_PUBLIC_BASE_URL=https://nkap.example.com"};

    private static String failure(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    @Test
    @DisplayName("with adapters configured, a default naming none of them fails startup, naming it and what is configured")
    void a_default_naming_no_configured_adapter_fails() {
        runner.withPropertyValues(MTN_CAMEROON).withPropertyValues("NKAP_PROVIDER_DEFAULT=mtn-gh")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failure(context)).contains("'mtn-gh'").contains("[mtn-cm]")
                            .contains("nkap.provider.default");
                });
    }

    @Test
    @DisplayName("only M-Pesa Kenya configured, the default left at application.yml's own, fails startup naming mtn-cm")
    void mpesa_only_with_the_default_left_alone_fails() {
        runner.withPropertyValues(MPESA_KENYA)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failure(context)).contains("'mtn-cm'").contains("[mpesa-ke]");
                });
    }

    @Test
    @DisplayName("with no adapter configured it starts, default and all: the chart's first install, with no credentials yet, depends on it")
    void no_adapter_configured_starts() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdapterRegistry.class).configuredProviders()).isEmpty();
        });
    }

    @Test
    @DisplayName("a default naming a configured adapter starts")
    void a_default_naming_a_configured_adapter_starts() {
        runner.withPropertyValues(MPESA_KENYA).withPropertyValues("NKAP_PROVIDER_DEFAULT=mpesa-ke")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(MTN_CAMEROON).run(context -> assertThat(context).hasNotFailed());
    }
}
