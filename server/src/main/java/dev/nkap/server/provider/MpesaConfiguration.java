package dev.nkap.server.provider;

import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mpesa.MpesaAdapter;
import dev.nkap.provider.mpesa.MpesaProfile;
import dev.nkap.server.provider.CredentialFileReader.CredentialFile;
import dev.nkap.server.provider.RereadMpesaProfile.Credential;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
 *
 * <p><strong>A credential supplied as a file is read again when the file changes</strong> (issue
 * #223, ADR 0015). Each adapter is handed a {@link RereadMpesaProfile} rather than a fixed profile.
 * Startup is unchanged: the profile is built and validated here, and a bad value still stops the
 * gateway. After startup, a bad value keeps the last valid one in use and is reported by one
 * warning and by {@link CredentialStaleness}'s gauge.
 */
@Configuration
@EnableConfigurationProperties(MpesaProperties.class)
class MpesaConfiguration {

    private static final String PREFIX = "nkap.provider.mpesa.installations";

    /**
     * Each configured installation's profile, validated now, in the order and with the messages
     * startup has always used, and re-read later from whichever of its credentials came from files.
     */
    @Bean
    MpesaProfiles mpesaProfiles(MpesaProperties properties, PublicBaseUrl publicBaseUrl, Environment environment) {
        CredentialFileReader files = CredentialFileReader.of(environment);
        Map<ProviderId, RereadMpesaProfile> profiles = new LinkedHashMap<>();
        List<MpesaProperties.Installation> installations = properties.installations();
        for (int i = 0; i < installations.size(); i++) {
            MpesaProperties.Installation installation = installations.get(i);
            if (!installation.isConfigured()) {
                continue;
            }
            ProviderId id = providerId(installation);
            requireCallbackAddress(id, publicBaseUrl);
            MpesaProfile startup = profile(i, installation);
            profiles.put(id, new RereadMpesaProfile(id, startup, credentialFiles(files, i), Clock.systemUTC()));
        }
        return new MpesaProfiles(profiles);
    }

    @Bean
    List<ProviderAdapter> mpesaAdapters(MpesaProperties properties, MpesaProfiles profiles) {
        List<ProviderAdapter> adapters = new ArrayList<>();
        for (MpesaProperties.Installation installation : properties.installations()) {
            if (installation.isConfigured()) {
                ProviderId id = providerId(installation);
                adapters.add(new MpesaAdapter(id, profiles.byId().get(id), installation.requestTimeout()));
            }
        }
        return adapters;
    }

    /** What {@code CredentialStalenessMetrics} exports: the installations that re-read a credential file. */
    @Bean
    CredentialStaleness mpesaCredentialStaleness(MpesaProfiles profiles) {
        Map<ProviderId, Supplier<Duration>> staleness = new LinkedHashMap<>();
        profiles.byId().forEach((id, profile) -> {
            if (profile.rereads()) {
                staleness.put(id, profile::staleFor);
            }
        });
        return new CredentialStaleness(staleness);
    }

    /** The configured installations' profiles, by provider id. */
    record MpesaProfiles(Map<ProviderId, RereadMpesaProfile> byId) {
    }

    /** The files installation {@code index}'s credentials came from; a credential set any other way is absent. */
    private static Map<Credential, CredentialFile> credentialFiles(CredentialFileReader files, int index) {
        Map<Credential, CredentialFile> found = new EnumMap<>(Credential.class);
        for (Credential credential : Credential.values()) {
            files.fileFor(PREFIX + "[" + index + "]." + credential.property)
                    .ifPresent(file -> found.put(credential, file));
        }
        return found;
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
