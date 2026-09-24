package dev.nkap.server.provider;

import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mpesa.MpesaAdapter;
import dev.nkap.provider.mpesa.MpesaProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One {@link MpesaAdapter} per configured M-Pesa installation (issue #215), registered as
 * {@code mpesa-<country>} — the shape {@link MtnConfiguration} established, where a country
 * installation is its own adapter with its own {@link ProviderId}.
 *
 * <p>Two things here have no MTN counterpart.
 *
 * <p><strong>A configured installation needs {@code nkap.public-base-url}, and the context
 * refuses to start without it.</strong> The adapter resolves a lost submission by
 * {@code CALLBACK} alone (ADR 0014) and refuses to submit without a callback address. Started
 * without a public base URL, a deployment would accept payments nothing could resolve, and the
 * mistake would surface as escalations, one payment at a time, long after it was made. The
 * rule belongs to the installation, not to the gateway: MTN resolves by query, so a deployment
 * with no M-Pesa installation starts without the property exactly as it always has.
 *
 * <p><strong>An incomplete installation is reported by property name, and never by
 * value.</strong> {@link MpesaProfile} would refuse a blank credential by itself, but it names
 * its own field ({@code passkey}), not the property a deployer sets. The checks below run
 * first and name {@code nkap.provider.mpesa.installations[n].<property>}. No message built
 * here contains a credential, whole or partial, whether present or missing.
 */
@Configuration
@EnableConfigurationProperties(MpesaProperties.class)
class MpesaConfiguration {

    private static final String PREFIX = "nkap.provider.mpesa.installations";

    @Bean
    List<ProviderAdapter> mpesaAdapters(MpesaProperties properties, PublicBaseUrl publicBaseUrl) {
        List<ProviderAdapter> adapters = new ArrayList<>();
        List<MpesaProperties.Installation> installations = properties.installations();
        for (int i = 0; i < installations.size(); i++) {
            MpesaProperties.Installation installation = installations.get(i);
            if (!installation.isConfigured()) {
                continue;
            }
            ProviderId id = providerId(installation);
            requireCallbackAddress(id, publicBaseUrl);
            adapters.add(new MpesaAdapter(id, profile(i, installation), installation.requestTimeout()));
        }
        return adapters;
    }

    /** The currency each installation settles in, and the country {@code POST /payments} routes on. */
    @Bean
    List<ProviderRouting> mpesaRoutings(MpesaProperties properties) {
        List<ProviderRouting> routes = new ArrayList<>();
        List<MpesaProperties.Installation> installations = properties.installations();
        for (int i = 0; i < installations.size(); i++) {
            MpesaProperties.Installation installation = installations.get(i);
            if (installation.isConfigured()) {
                requirePresent(installation.baseUrl(), i, "base-url");
                requirePresent(installation.currency(), i, "currency");
                routes.add(new ProviderRouting(providerId(installation), country(installation),
                        installation.currency(), installation.baseUrl().toString()));
            }
        }
        return routes;
    }

    private static void requireCallbackAddress(ProviderId id, PublicBaseUrl publicBaseUrl) {
        if (!publicBaseUrl.isConfigured()) {
            throw new IllegalStateException("M-Pesa installation " + id + " requires nkap.public-base-url "
                    + "(NKAP_PUBLIC_BASE_URL): M-Pesa resolves a payment whose submission was lost only by "
                    + "its callback, and without a public base URL there is no address for that callback. "
                    + "Set it, or leave the installation's country blank to not configure it.");
        }
    }

    private static MpesaProfile profile(int index, MpesaProperties.Installation installation) {
        requirePresent(installation.baseUrl(), index, "base-url");
        requireText(installation.businessShortCode(), index, "business-short-code");
        requireText(installation.passkey(), index, "passkey");
        requireText(installation.consumerKey(), index, "consumer-key");
        requireText(installation.consumerSecret(), index, "consumer-secret");
        requirePresent(installation.currency(), index, "currency");
        return new MpesaProfile(
                installation.baseUrl(),
                installation.businessShortCode(),
                installation.passkey(),
                installation.consumerKey(),
                installation.consumerSecret(),
                installation.currency());
    }

    private static void requireText(String value, int index, String property) {
        if (value == null || value.isBlank()) {
            throw missing(index, property);
        }
    }

    private static void requirePresent(Object value, int index, String property) {
        if (value == null) {
            throw missing(index, property);
        }
    }

    private static IllegalStateException missing(int index, String property) {
        return new IllegalStateException(PREFIX + "[" + index + "]." + property
                + " is blank on an installation whose country is set; set it, or leave the country blank "
                + "to not configure this installation");
    }

    /** {@code mpesa-} plus the installation's country, e.g. {@code mpesa-ke}. */
    private static ProviderId providerId(MpesaProperties.Installation installation) {
        return ProviderId.of("mpesa-" + country(installation));
    }

    private static String country(MpesaProperties.Installation installation) {
        return installation.country().strip().toLowerCase(Locale.ROOT);
    }
}
