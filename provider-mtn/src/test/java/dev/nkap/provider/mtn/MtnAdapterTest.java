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
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MTN facade: one adapter, two products, routing on <strong>what it is given</strong>.
 * {@code submit} on the intent's operation, {@code query} on the capability argument the
 * caller passes (issue #67 — the contract carries it, ADR 0008, so the #62 lookup is gone).
 * And a Collections call keeps a token independent of a Disbursements one.
 */
class MtnAdapterTest {

    private final Currency eur = Currency.EUR;

    private MtnProfile profileAt(java.net.URI base) {
        return new MtnProfile(base, "sandbox", "sub", "user", "key", eur, "sandbox");
    }

    private PaymentIntent intent(Capability.Operation operation) {
        return new PaymentIntent(operation, Money.of(5000, eur), "46733123453", "n", "n", Map.of());
    }

    private MtnAdapter facadeAt(StubMtn mtn, boolean withDisbursements) {
        MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2));
        MtnDisbursementsAdapter disbursements = withDisbursements
                ? new MtnDisbursementsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(2))
                : null;
        return new MtnAdapter(collections, disbursements);
    }

    @Test
    @DisplayName("the facade takes only the two product adapters — no reference lookup wired in any more")
    void the_facade_takes_no_reference_lookup() {
        Constructor<?>[] constructors = MtnAdapter.class.getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes())
                .as("(collections, disbursements) — the #62 Function<ReferenceId, Optional<Capability>> is gone")
                .containsExactly(MtnCollectionsAdapter.class, MtnDisbursementsAdapter.class);
    }

    @Test
    @DisplayName("capabilities are the union; with no disbursements product it is COLLECT alone")
    void capabilities_reflect_what_is_configured() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            assertThat(facadeAt(mtn, true).capabilities())
                    .containsExactlyInAnyOrder(Capability.Operation.COLLECT, Capability.Operation.DISBURSE);
            assertThat(facadeAt(mtn, false).capabilities()).containsExactly(Capability.Operation.COLLECT);
            assertThat(facadeAt(mtn, false).id()).isEqualTo(ProviderId.of("mtn"));
        }
    }

    @Test
    @DisplayName("a DISBURSE call when disbursements is not configured fails with a clear message, not a NullPointerException")
    void an_unconfigured_disburse_is_a_clear_error() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            MtnAdapter facade = facadeAt(mtn, false);
            assertThatThrownBy(() -> facade.submit(intent(Capability.Operation.DISBURSE), ReferenceId.newReference()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disbursement");
            assertThatThrownBy(() -> facade.query(ReferenceId.newReference(), Capability.Operation.DISBURSE))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("query routes on its capability argument: DISBURSE hits /disbursement/..., COLLECT hits /collection/...")
    void query_routes_on_the_capability_argument() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().endsWith("/token/")
                    ? new StubResponse(200, StubMtn.tokenJson("t", 3600))
                    : new StubResponse(200, "{\"status\":\"SUCCESSFUL\"}"));
            MtnAdapter facade = facadeAt(mtn, true);
            ReferenceId ref = ReferenceId.newReference();

            facade.query(ref, Capability.Operation.DISBURSE);
            facade.query(ref, Capability.Operation.COLLECT);

            // The same reference, two capabilities, two different product URLs — asserted on
            // the outgoing path, not on any mock.
            assertThat(mtn.countPath("/disbursement/v1_0/transfer/" + ref)).isEqualTo(1);
            assertThat(mtn.countPath("/collection/v1_0/requesttopay/" + ref)).isEqualTo(1);
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
            MtnAdapter facade = facadeAt(mtn, true);

            facade.submit(intent(Capability.Operation.COLLECT), ReferenceId.newReference());
            facade.submit(intent(Capability.Operation.DISBURSE), ReferenceId.newReference());
            facade.submit(intent(Capability.Operation.COLLECT), ReferenceId.newReference());   // reuses the collections token
            assertThat(facade.query(ReferenceId.newReference(), Capability.Operation.DISBURSE).state())
                    .isEqualTo(PaymentState.SUCCEEDED);                                // reuses the disbursements token

            assertThat(mtn.countPath("/collection/token/")).as("two collections calls, one token").isEqualTo(1);
            assertThat(mtn.countPath("/disbursement/token/")).as("a disbursements submit + query, one token").isEqualTo(1);
            assertThat(mtn.countPath("/collection/v1_0/requesttopay")).isEqualTo(2);
            assertThat(mtn.countPath("/disbursement/v1_0/transfer")).isEqualTo(1);
        }
    }
}
