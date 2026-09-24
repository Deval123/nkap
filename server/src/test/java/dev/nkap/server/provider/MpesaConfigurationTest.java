package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.support.LogCapture;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Issue #215's wiring, against the committed {@code application.yml} rather than a copy of
 * it: {@link ConfigDataApplicationContextInitializer} loads the real file, so what a deployment
 * gets from setting nothing at all is what these tests start from.
 *
 * <p>Every credential below is a marker string that must never appear in anything the context
 * says about itself — no exception message, no cause, no log line — on the failure paths
 * above all, which is where a configuration slice leaks one by accident.
 */
class MpesaConfigurationTest {

    private static final String PASSKEY = "passkey-that-must-never-be-printed";
    private static final String CONSUMER_KEY = "consumer-key-that-must-never-be-printed";
    private static final String CONSUMER_SECRET = "consumer-secret-that-must-never-be-printed";
    private static final String MTN_API_KEY = "mtn-api-key-that-must-never-be-printed";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MtnPropertiesBinding.class, PublicBaseUrl.class, MtnConfiguration.class,
                    MpesaConfiguration.class, ConfiguredAdapterRegistry.class)
            .withPropertyValues(mtnCameroon());

    /** The application finds MtnProperties by scanning; this context has no scan to find it with. */
    @EnableConfigurationProperties(MtnProperties.class)
    static class MtnPropertiesBinding {
    }

    /** MTN's first slot, complete: every test here is a deployment that already serves MTN. */
    private static String[] mtnCameroon() {
        String slot = "nkap.provider.mtn.installations[0].";
        return new String[] {
                slot + "base-url=http://localhost:1",
                slot + "target-environment=sandbox",
                slot + "subscription-key=mtn-subscription-key",
                slot + "api-user=mtn-api-user",
                slot + "api-key=" + MTN_API_KEY,
                slot + "currency=XAF",
                slot + "country=cm",
        };
    }

    /** The Kenya slot, complete except for whatever a test leaves out. */
    private static List<String> mpesaKenya(String... without) {
        String slot = "nkap.provider.mpesa.installations[0].";
        List<String> properties = new ArrayList<>(List.of(
                slot + "base-url=http://localhost:1",
                slot + "business-short-code=174379",
                slot + "passkey=" + PASSKEY,
                slot + "consumer-key=" + CONSUMER_KEY,
                slot + "consumer-secret=" + CONSUMER_SECRET,
                slot + "currency=KES",
                slot + "country=ke"));
        for (String property : without) {
            properties.removeIf(p -> p.startsWith(slot + property + "="));
        }
        return properties;
    }

    private static final String PUBLIC_BASE_URL = "nkap.public-base-url=https://gateway.example.com";

    @Test
    @DisplayName("an M-Pesa installation is registered as mpesa-<country>, and its country routes to it")
    void a_configured_installation_is_registered_and_routable_by_country() {
        runner.withPropertyValues(mpesaKenya().toArray(String[]::new))
                .withPropertyValues(PUBLIC_BASE_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AdapterRegistry registry = context.getBean(AdapterRegistry.class);
                    assertThat(registry.configuredProviders())
                            .containsExactlyInAnyOrder(ProviderId.of("mtn-cm"), ProviderId.of("mpesa-ke"));
                    assertThat(registry.providerForCountry("ke")).contains(ProviderId.of("mpesa-ke"));
                    assertThat(registry.providerForCountry("KE")).contains(ProviderId.of("mpesa-ke"));
                    assertThat(registry.providerForCountry("cm")).contains(ProviderId.of("mtn-cm"));
                    assertThat(registry.settlementCurrency(ProviderId.of("mpesa-ke")))
                            .contains(dev.nkap.core.money.Currency.KES);
                });
    }

    @Test
    @DisplayName("the committed Kenya slot is blank by default: a deployment configured with MTN only starts unchanged")
    void a_blank_slot_is_skipped_and_mtn_only_starts_unchanged() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdapterRegistry.class).configuredProviders())
                    .containsExactly(ProviderId.of("mtn-cm"));
        });
    }

    @Test
    @DisplayName("a deployment with MTN only and no public base URL still starts: the callback rule is M-Pesa's, not everyone's")
    void mtn_only_without_a_public_base_url_starts() {
        runner.withPropertyValues("nkap.public-base-url=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PublicBaseUrl.class).isConfigured()).isFalse();
                    assertThat(context.getBean(AdapterRegistry.class).configuredProviders())
                            .containsExactly(ProviderId.of("mtn-cm"));
                });
    }

    @Test
    @DisplayName("an M-Pesa installation without nkap.public-base-url fails startup, and the failure names the property")
    void an_installation_without_a_public_base_url_fails_startup_naming_it() {
        try (LogCapture root = new LogCapture("ROOT")) {
            runner.withPropertyValues(mpesaKenya().toArray(String[]::new))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(rootCauseMessage(context))
                                .contains("mpesa-ke")
                                .contains("nkap.public-base-url")
                                .contains("NKAP_PUBLIC_BASE_URL")
                                .contains("callback");
                        assertNoCredentialIn(everythingSaid(context, root));
                    });
        }
    }

    @Test
    @DisplayName("a blank credential on a configured installation fails startup naming the property, never a value")
    void a_blank_credential_is_named_by_property_not_by_value() {
        for (String credential : List.of("passkey", "consumer-key", "consumer-secret", "business-short-code")) {
            try (LogCapture root = new LogCapture("ROOT")) {
                runner.withPropertyValues(mpesaKenya(credential).toArray(String[]::new))
                        .withPropertyValues(PUBLIC_BASE_URL)
                        .run(context -> {
                            assertThat(context).as(credential).hasFailed();
                            assertThat(rootCauseMessage(context))
                                    .as(credential)
                                    .contains("nkap.provider.mpesa.installations[0]." + credential);
                            assertNoCredentialIn(everythingSaid(context, root));
                        });
            }
        }
    }

    @Test
    @DisplayName("a binding failure on a filled-in installation quotes the value it could not bind, and no credential")
    void a_binding_failure_does_not_quote_a_credential() {
        for (String malformed : List.of(
                "nkap.provider.mpesa.installations[0].currency=NOT-A-CURRENCY",
                "nkap.provider.mpesa.installations[0].request-timeout=not-a-duration",
                "nkap.provider.mpesa.installations[0].base-url=http://bad host/")) {
            try (LogCapture root = new LogCapture("ROOT")) {
                runner.withPropertyValues(mpesaKenya().toArray(String[]::new))
                        .withPropertyValues(PUBLIC_BASE_URL, malformed)
                        .run(context -> {
                            assertThat(context).as(malformed).hasFailed();
                            assertNoCredentialIn(everythingSaid(context, root));
                        });
            }
        }
    }

    private static String rootCauseMessage(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /** The whole failure as it would be printed — every cause, every message — plus every log line. */
    private static String everythingSaid(AssertableApplicationContext context, LogCapture root) {
        StringWriter out = new StringWriter();
        context.getStartupFailure().printStackTrace(new PrintWriter(out));
        for (ILoggingEvent event : root.events()) {
            out.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                out.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return out.toString();
    }

    private static void assertNoCredentialIn(String said) {
        for (String secret : List.of(PASSKEY, CONSUMER_KEY, CONSUMER_SECRET, MTN_API_KEY)) {
            // Whole or partial: the distinctive head of each marker is enough to catch a
            // truncated or masked-but-leaky rendering.
            assertThat(said).doesNotContain(secret.substring(0, 16));
        }
    }
}
