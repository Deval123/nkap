package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.support.LogCapture;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Credentials as files named after the variables they replace, against the committed
 * {@code application.yml}: {@link ConfigDataApplicationContextInitializer} loads the real file,
 * including its {@code optional:configtree:} import, pointed at a directory each test writes.
 * The process environment is replaced with one built here, so both guards read variable names
 * exactly where they read the real ones.
 *
 * <p>Every value below is a marker that must never appear in anything the context says about
 * itself, on the failure paths above all.
 */
class CredentialFilesTest {

    private static final String PASSKEY_IN_FILE = "passkey-in-a-file-that-must-never-be-printed";
    private static final String PASSKEY_IN_VARIABLE = "passkey-in-a-variable-that-must-never-be-printed";
    private static final String STRAY_KEY_IN_FILE = "stray-key-in-a-file-that-must-never-be-printed";

    @TempDir
    Path secrets;

    /** M-Pesa Kenya, complete except the passkey, as variables. */
    private static Map<String, Object> kenyaWithoutPasskey() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("NKAP_PROVIDER_MPESA_KE_COUNTRY", "ke");
        variables.put("NKAP_PROVIDER_MPESA_KE_BASE_URL", "http://localhost:1");
        variables.put("NKAP_PROVIDER_MPESA_KE_BUSINESS_SHORT_CODE", "174379");
        variables.put("NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY", "consumer-key-that-must-never-be-printed");
        variables.put("NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET", "consumer-secret-that-must-never-be-printed");
        variables.put("NKAP_PUBLIC_BASE_URL", "https://gateway.example.com");
        return variables;
    }

    private ApplicationContextRunner runner(Map<String, Object> variables, Path importedDirectory) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("NKAP_SECRETS_DIR=" + importedDirectory)
                .withUserConfiguration(ProviderSlotGuard.class, CredentialSourceGuard.class, PublicBaseUrl.class,
                        MpesaConfiguration.class, ConfiguredAdapterRegistry.class);
    }

    private void file(String name, String content) throws IOException {
        Files.writeString(secrets.resolve(name), content);
    }

    // --- what works ------------------------------------------------------------------------

    @Test
    @DisplayName("a credential supplied only by a file named after its variable starts the gateway, and the installation exists")
    void a_file_only_credential_configures_the_installation() throws IOException {
        file("NKAP_PROVIDER_MPESA_KE_PASSKEY", PASSKEY_IN_FILE);

        runner(kenyaWithoutPasskey(), secrets).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdapterRegistry.class).find(ProviderId.of("mpesa-ke"))).isPresent();
            assertThat(context.getBean(MpesaProperties.class).installations().get(0).passkey())
                    .isEqualTo(PASSKEY_IN_FILE);
        });
    }

    /**
     * The assertion that would have caught the design this one replaced. A file named after the
     * bound property ({@code nkap.provider.mpesa.installations[0].passkey}) made the config tree
     * the only source of the whole list, so the element lost every field the YAML supplied. A file
     * named after the variable feeds one placeholder and leaves the list to the YAML.
     */
    @Test
    @DisplayName("application.yml's own defaults survive a file-supplied credential: the currency is still KES, the request timeout still PT20S")
    void the_yaml_defaults_survive_a_file_supplied_credential() throws IOException {
        file("NKAP_PROVIDER_MPESA_KE_PASSKEY", PASSKEY_IN_FILE);

        runner(kenyaWithoutPasskey(), secrets).run(context -> {
            assertThat(context).hasNotFailed();
            MpesaProperties.Installation kenya = context.getBean(MpesaProperties.class).installations().get(0);
            assertThat(kenya.currency()).isEqualTo(Currency.KES);
            assertThat(kenya.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(kenya.country()).isEqualTo("ke");
        });
    }

    /**
     * {@code echo value > NKAP_PROVIDER_MPESA_KE_PASSKEY} writes a trailing newline, and that is
     * how a person creates these files. Measured against a real jar before this test was written:
     * one trailing {@code \n}, or {@code \r\n}, is removed, and the operator receives the value
     * without it. This keeps that true for whoever regenerates the example files with a script.
     *
     * <p>Only one. The same measurement found that two trailing newlines are not trimmed at all,
     * and a trailing space is not either: both reach the operator as part of the value.
     */
    @ParameterizedTest(name = "ending in {0}")
    @ValueSource(strings = {"\n", "\r\n"})
    @DisplayName("a credential file ending in one newline configures the installation exactly as the same file without it")
    void one_trailing_newline_configures_the_installation_exactly_as_none(String newline) throws IOException {
        file("NKAP_PROVIDER_MPESA_KE_PASSKEY", PASSKEY_IN_FILE);
        MpesaProperties.Installation[] withoutNewline = new MpesaProperties.Installation[1];
        runner(kenyaWithoutPasskey(), secrets).run(context -> {
            assertThat(context).hasNotFailed();
            withoutNewline[0] = context.getBean(MpesaProperties.class).installations().get(0);
        });

        file("NKAP_PROVIDER_MPESA_KE_PASSKEY", PASSKEY_IN_FILE + newline);
        runner(kenyaWithoutPasskey(), secrets).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdapterRegistry.class).find(ProviderId.of("mpesa-ke"))).isPresent();
            MpesaProperties.Installation withNewline = context.getBean(MpesaProperties.class).installations().get(0);
            assertThat(withNewline.passkey()).isEqualTo(PASSKEY_IN_FILE);
            assertThat(withNewline).isEqualTo(withoutNewline[0]);
        });
    }

    @Test
    @DisplayName("variables alone, with no imported directory at all, configure the installation exactly as before")
    void variables_alone_still_work_without_a_directory() {
        Map<String, Object> variables = kenyaWithoutPasskey();
        variables.put("NKAP_PROVIDER_MPESA_KE_PASSKEY", PASSKEY_IN_VARIABLE);

        runner(variables, secrets.resolve("does-not-exist")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdapterRegistry.class).find(ProviderId.of("mpesa-ke"))).isPresent();
            assertThat(context.getBean(MpesaProperties.class).installations().get(0).passkey())
                    .isEqualTo(PASSKEY_IN_VARIABLE);
        });
    }

    @Test
    @DisplayName("an absent imported directory starts a gateway that configures nothing: the import is optional")
    void an_absent_directory_starts() {
        runner(new HashMap<>(), secrets.resolve("does-not-exist")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdapterRegistry.class).configuredProviders()).isEmpty();
        });
    }

    // --- what fails ------------------------------------------------------------------------

    @Test
    @DisplayName("one name as both a variable and a file fails startup, naming the variable and both sources, with neither value in the failure")
    void a_name_from_both_sources_is_refused_without_either_value() throws IOException {
        file("NKAP_PROVIDER_MPESA_KE_PASSKEY", PASSKEY_IN_FILE);
        Map<String, Object> variables = kenyaWithoutPasskey();
        variables.put("NKAP_PROVIDER_MPESA_KE_PASSKEY", PASSKEY_IN_VARIABLE);

        try (LogCapture root = new LogCapture("ROOT")) {
            runner(variables, secrets).run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootCauseMessage(context))
                        .contains("NKAP_PROVIDER_MPESA_KE_PASSKEY")
                        .contains("environment variable")
                        .contains("file in the imported credentials directory")
                        .contains("Remove one of them");
                assertThat(everythingSaid(context, root))
                        .as("neither the variable's value nor the file's contents, whole or partial")
                        .doesNotContain(PASSKEY_IN_FILE)
                        .doesNotContain(PASSKEY_IN_FILE.substring(0, 16))
                        .doesNotContain(PASSKEY_IN_VARIABLE)
                        .doesNotContain(PASSKEY_IN_VARIABLE.substring(0, 16));
            });
        }
    }

    @Test
    @DisplayName("an empty file for a required credential fails startup naming the property, the answer a blank variable gives")
    void an_empty_file_is_a_blank_credential() throws IOException {
        file("NKAP_PROVIDER_MPESA_KE_PASSKEY", "");

        runner(kenyaWithoutPasskey(), secrets).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseMessage(context)).contains("nkap.provider.mpesa.installations[0].passkey is blank");
        });
    }

    @Test
    @DisplayName("a file naming an undeclared country fails startup, says it was a file, and never prints its contents")
    void a_file_for_an_undeclared_country_is_refused_as_a_file() throws IOException {
        file("NKAP_PROVIDER_MTN_CI_API_KEY", STRAY_KEY_IN_FILE);

        try (LogCapture root = new LogCapture("ROOT")) {
            runner(new HashMap<>(), secrets).run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootCauseMessage(context))
                        .contains("MTN CI")
                        .contains("a file of NKAP_PROVIDER_MTN_CI_* is in the imported credentials directory")
                        .contains("Remove those files");
                assertThat(everythingSaid(context, root))
                        .doesNotContain(STRAY_KEY_IN_FILE)
                        .doesNotContain(STRAY_KEY_IN_FILE.substring(0, 16));
            });
        }
    }

    private static String rootCauseMessage(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /** The startup failure with every cause, and every log line with its throwable. */
    private static String everythingSaid(AssertableApplicationContext context, LogCapture root) {
        StringWriter printed = new StringWriter();
        context.getStartupFailure().printStackTrace(new PrintWriter(printed));
        for (ILoggingEvent event : root.events()) {
            printed.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                printed.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return printed.toString();
    }
}
