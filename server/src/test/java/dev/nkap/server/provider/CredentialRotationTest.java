package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mpesa.MpesaProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The use-time read wired against the committed {@code application.yml}, as
 * {@link CredentialFilesTest} wires slice A: which file feeds which credential is found through the
 * file's own placeholders, not a copy of them, and only a credential that came from a file changes.
 */
class CredentialRotationTest {

    private static final ProviderId KENYA = ProviderId.of("mpesa-ke");

    @TempDir
    Path secrets;

    private Instant fileTime = Instant.parse("2026-09-25T11:00:00Z");

    /** M-Pesa Kenya as variables, except whatever a test supplies as a file. */
    private static Map<String, Object> kenya(String... asFiles) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("NKAP_PROVIDER_MPESA_KE_COUNTRY", "ke");
        variables.put("NKAP_PROVIDER_MPESA_KE_BASE_URL", "http://localhost:1");
        variables.put("NKAP_PROVIDER_MPESA_KE_BUSINESS_SHORT_CODE", "174379");
        variables.put("NKAP_PROVIDER_MPESA_KE_PASSKEY", "passkey-as-variable");
        variables.put("NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY", "consumer-key-as-variable");
        variables.put("NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET", "consumer-secret-as-variable");
        variables.put("NKAP_PUBLIC_BASE_URL", "https://gateway.example.com");
        for (String name : asFiles) {
            variables.remove(name);
        }
        return variables;
    }

    private ApplicationContextRunner runner(Map<String, Object> variables) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("NKAP_SECRETS_DIR=" + secrets)
                .withUserConfiguration(ProviderSlotGuard.class, CredentialSourceGuard.class, PublicBaseUrl.class,
                        MpesaConfiguration.class, ConfiguredAdapterRegistry.class);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(secrets.resolve(name), content);
        fileTime = fileTime.plusSeconds(1);
        Files.setLastModifiedTime(secrets.resolve(name), FileTime.from(fileTime));
    }

    private static MpesaProfile kenyaNow(org.springframework.context.ApplicationContext context) {
        return context.getBean(MpesaConfiguration.MpesaProfiles.class).byId().get(KENYA).get();
    }

    @Test
    @DisplayName("a passkey supplied as a file and rotated on disk is the running gateway's next passkey, with no restart")
    void a_rotated_passkey_file_is_used_by_the_running_gateway() throws IOException {
        write("NKAP_PROVIDER_MPESA_KE_PASSKEY", "passkey-in-a-file-one\n");

        runner(kenya("NKAP_PROVIDER_MPESA_KE_PASSKEY")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(kenyaNow(context).passkey()).isEqualTo("passkey-in-a-file-one");

            write("NKAP_PROVIDER_MPESA_KE_PASSKEY", "passkey-in-a-file-two\n");

            assertThat(kenyaNow(context).passkey()).isEqualTo("passkey-in-a-file-two");
            assertThat(kenyaNow(context).consumerKey()).as("a variable, never re-read")
                    .isEqualTo("consumer-key-as-variable");
            assertThat(context.getBean(CredentialStaleness.class).installations()).containsExactly(KENYA);
            assertThat(context.getBean(CredentialStaleness.class).staleFor(KENYA)).isZero();
        });
    }

    @Test
    @DisplayName("all three credentials as files: each is found through its own placeholder and rotates on its own")
    void each_credential_file_is_found_through_its_placeholder() throws IOException {
        write("NKAP_PROVIDER_MPESA_KE_PASSKEY", "passkey-one");
        write("NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY", "consumer-key-one");
        write("NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET", "consumer-secret-one");

        runner(kenya("NKAP_PROVIDER_MPESA_KE_PASSKEY", "NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY",
                "NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET")).run(context -> {
            assertThat(context).hasNotFailed();

            write("NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET", "consumer-secret-two");

            MpesaProfile now = kenyaNow(context);
            assertThat(now.passkey()).isEqualTo("passkey-one");
            assertThat(now.consumerKey()).isEqualTo("consumer-key-one");
            assertThat(now.consumerSecret()).isEqualTo("consumer-secret-two");
        });
    }

    @Test
    @DisplayName("credentials supplied only as variables are never re-read, and the installation has no staleness gauge")
    void variables_alone_are_not_reread() {
        runner(kenya()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(kenyaNow(context).passkey()).isEqualTo("passkey-as-variable");
            assertThat(context.getBean(MpesaConfiguration.MpesaProfiles.class).byId().get(KENYA).rereads()).isFalse();
            assertThat(context.getBean(CredentialStaleness.class).installations()).isEmpty();
        });
    }
}
