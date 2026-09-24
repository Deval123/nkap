package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * {@link ProviderSlotGuard} in a real context, reading a process environment this test controls:
 * the {@code systemEnvironment} property source is replaced with one built here, so the guard
 * reads it exactly where it reads the real one.
 *
 * <p>The test that matters is the first. This is the only code in the gateway that enumerates the
 * environment holding every operator credential, and the property it must keep is that no value
 * ever leaves it, not even in the failure it exists to raise.
 */
class ProviderSlotGuardTest {

    private static final String FAKE_SECRET = "fake-secret-value-that-must-never-be-printed-7f3a";

    private static ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
                .withUserConfiguration(ProviderSlotGuard.class);
    }

    @Test
    @DisplayName("an undeclared country's credential fails startup without its value, or its variable name, reaching the failure")
    void no_value_reaches_the_failure() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("NKAP_PROVIDER_MTN_CI_API_KEY", FAKE_SECRET);
        variables.put("NKAP_PROVIDER_MTN_CI_COUNTRY", "ci");

        withEnvironment(variables).run(context -> {
            assertThat(context).hasFailed();
            StringWriter printed = new StringWriter();
            context.getStartupFailure().printStackTrace(new PrintWriter(printed));

            assertThat(printed.toString())
                    .as("the failure names the pair")
                    .contains("MTN CI")
                    .contains("NKAP_PROVIDER_MTN_CI_*");
            assertThat(printed.toString())
                    .as("no value, whole or partial, anywhere in the failure or its causes")
                    .doesNotContain(FAKE_SECRET)
                    .doesNotContain(FAKE_SECRET.substring(0, 16));
            assertThat(printed.toString())
                    .as("no individual variable name from the credential family")
                    .doesNotContain("_API_KEY");
        });
    }

    @Test
    @DisplayName("an environment naming only declared slots starts")
    void declared_slots_start() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("NKAP_PROVIDER_MTN_CM_API_KEY", FAKE_SECRET);
        variables.put("NKAP_PROVIDER_MPESA_KE_PASSKEY", FAKE_SECRET);
        variables.put("NKAP_PROVIDER_DEFAULT", "mtn-cm");

        withEnvironment(variables).run(context -> assertThat(context).hasNotFailed());
    }
}
