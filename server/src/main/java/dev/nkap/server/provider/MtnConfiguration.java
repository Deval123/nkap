package dev.nkap.server.provider;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mtn.MtnAdapter;
import dev.nkap.provider.mtn.MtnCollectionsAdapter;
import dev.nkap.provider.mtn.MtnDisbursementsAdapter;
import dev.nkap.provider.mtn.MtnProfile;
import dev.nkap.server.payment.PaymentRepository;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The MTN adapter: one {@link ProviderAdapter} for the operator, Collections and
 * Disbursements behind it (issue #62). Each product is a whole {@link MtnProfile} — its own
 * subscription key and API user/key — sharing only the installation's base URL, target
 * environment, currency and country.
 */
@Configuration
class MtnConfiguration {

    @Bean
    ProviderAdapter mtnAdapter(MtnProperties properties, PaymentRepository payments) {
        MtnCollectionsAdapter collections = new MtnCollectionsAdapter(
                collectionProfile(properties), properties.requestTimeout());

        // Disbursements is a separate product; a deployment that only collects leaves
        // nkap.provider.mtn.disbursement.* unset, the adapter is null, and MtnAdapter neither
        // advertises DISBURSE nor lets one through.
        MtnDisbursementsAdapter disbursements = properties.disbursement().isConfigured()
                ? new MtnDisbursementsAdapter(disbursementProfile(properties), properties.requestTimeout())
                : null;

        // query() and parseCallback() are handed a reference, not an intent, so the facade
        // routes query() to the right product by reading the operation off the payment the
        // gateway already recorded. The adapter does not see payments; the composition root
        // does, so the lookup is wired here.
        Function<ReferenceId, Optional<Capability>> productOf =
                reference -> payments.findByReference(reference).map(payment -> payment.intent().operation());

        return new MtnAdapter(collections, disbursements, productOf);
    }

    /**
     * The routing for this MTN installation: the currency it settles in, taken from
     * configuration so the gateway can turn away a request in another currency before a
     * payment exists. One currency for the installation — collections and disbursements
     * settle in the same one.
     */
    @Bean
    ProviderRouting mtnRouting(MtnProperties properties) {
        return new ProviderRouting(ProviderId.of("mtn"), properties.currency());
    }

    private static MtnProfile collectionProfile(MtnProperties properties) {
        return new MtnProfile(
                properties.baseUrl(),
                properties.targetEnvironment(),
                properties.subscriptionKey(),
                properties.apiUser(),
                properties.apiKey(),
                properties.currency(),
                properties.country());
    }

    private static MtnProfile disbursementProfile(MtnProperties properties) {
        MtnProperties.Disbursement d = properties.disbursement();
        // MtnProfile rejects blanks, so an unconfigured disbursement block fails here at
        // startup — clearer than the first DISBURSE payment failing. A deployment that only
        // collects is expected to set these too, or not enable disbursement routing; the
        // demo and the tests provide them.
        return new MtnProfile(
                properties.baseUrl(),
                properties.targetEnvironment(),
                d.subscriptionKey(),
                d.apiUser(),
                d.apiKey(),
                properties.currency(),
                properties.country());
    }
}
