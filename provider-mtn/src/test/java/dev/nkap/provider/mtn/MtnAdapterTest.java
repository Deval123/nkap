package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mtn.StubMtn.StubResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MTN facade: one adapter, two products. Dispatch on the intent's operation for
 * {@code submit}; route {@code query} by the operation the gateway recorded for the
 * reference; and — the property this class is really here for — a Collections call and a
 * Disbursements call keep <strong>independent</strong> tokens: one never renews the other's.
 */
class MtnAdapterTest {

    private final Currency eur = Currency.EUR;

    private MtnProfile profileAt(java.net.URI base) {
        return new MtnProfile(base, "sandbox", "sub", "user", "key", eur, "sandbox");
    }

    private PaymentIntent intent(Capability operation) {
        return new PaymentIntent(operation, Money.of(5000, eur), "46733123453", "n", "n", Map.of());
    }

    @Test
    @DisplayName("capabilities are the union; with no disbursements product it is COLLECT alone")
    void capabilities_reflect_what_is_configured() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
            MtnDisbursementsAdapter disbursements = new MtnDisbursementsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));

            assertThat(new MtnAdapter(collections, disbursements, ref -> Optional.empty()).capabilities())
                    .containsExactlyInAnyOrder(Capability.COLLECT, Capability.DISBURSE);
            assertThat(new MtnAdapter(collections, null, ref -> Optional.empty()).capabilities())
                    .containsExactly(Capability.COLLECT);
        }
    }

    @Test
    @DisplayName("a DISBURSE submit when disbursements is not configured fails with a clear message, not a NullPointerException")
    void an_unconfigured_disburse_is_a_clear_error() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
            MtnAdapter facade = new MtnAdapter(collections, null, ref -> Optional.empty());

            assertThatThrownBy(() -> facade.submit(intent(Capability.DISBURSE), ReferenceId.newReference()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disbursement");
        }
    }

    @Test
    @DisplayName("a Collections call and a Disbursements call keep independent tokens — neither renews the other's")
    void the_two_products_keep_independent_tokens() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> switch (request.path()) {
                case "/collection/token/", "/disbursement/token/" -> new StubResponse(200, StubMtn.tokenJson("t", 3600));
                case "/collection/v1_0/requesttopay", "/disbursement/v1_0/transfer" -> new StubResponse(202, "");
                default -> new StubResponse(200, "{\"status\":\"SUCCESSFUL\"}");
            });

            MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
            MtnDisbursementsAdapter disbursements = new MtnDisbursementsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
            ReferenceId disburseRef = ReferenceId.newReference();
            Map<ReferenceId, Capability> product = new HashMap<>();
            product.put(disburseRef, Capability.DISBURSE);
            MtnAdapter facade = new MtnAdapter(collections, disbursements,
                    ref -> Optional.ofNullable(product.get(ref)));

            facade.submit(intent(Capability.COLLECT), ReferenceId.newReference());
            facade.submit(intent(Capability.DISBURSE), disburseRef);
            facade.submit(intent(Capability.COLLECT), ReferenceId.newReference());  // reuses the collection token
            assertThat(facade.query(disburseRef).state()).isEqualTo(PaymentState.SUCCEEDED);  // reuses the disbursement token

            assertThat(mtn.countPath("/collection/token/"))
                    .as("two collections calls, one collections token")
                    .isEqualTo(1);
            assertThat(mtn.countPath("/disbursement/token/"))
                    .as("a disbursement submit + query, one disbursement token")
                    .isEqualTo(1);
            assertThat(mtn.countPath("/collection/v1_0/requesttopay")).isEqualTo(2);
            assertThat(mtn.countPath("/disbursement/v1_0/transfer")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("query routes to the product the reference was recorded under")
    void query_routes_by_recorded_operation() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().endsWith("/token/")
                    ? new StubResponse(200, StubMtn.tokenJson("t", 3600))
                    : new StubResponse(200, "{\"status\":\"PENDING\"}"));

            MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
            MtnDisbursementsAdapter disbursements = new MtnDisbursementsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
            ReferenceId ref = ReferenceId.newReference();
            MtnAdapter facade = new MtnAdapter(collections, disbursements, r -> Optional.of(Capability.DISBURSE));

            facade.query(ref);

            assertThat(mtn.countPath("/disbursement/v1_0/transfer/" + ref)).isEqualTo(1);
            assertThat(mtn.countPath("/collection/v1_0/requesttopay/" + ref)).isZero();
        }
    }

    @Test
    @DisplayName("the facade advertises the mtn id")
    void identity() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
            assertThat(new MtnAdapter(collections, null, ref -> Optional.empty()).id())
                    .isEqualTo(ProviderId.of("mtn"));
        }
    }
}
