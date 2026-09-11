package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import dev.nkap.provider.Capability;
import dev.nkap.provider.HolderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.mtn.StubMtn.StubResponse;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code validateHolder()}, driven through a stub (issue #72). Written second, per the
 * plan: the "does not answer is unknown, never inactive" rule is the same invariant as
 * {@link MtnBalanceTest}'s, and the one someone will be tempted to default.
 */
class MtnHolderValidationTest {

    private static final String MSISDN = "46733123453";

    private MtnProfile profileAt(URI base) {
        return new MtnProfile(base, "sandbox", "sub-key", "api-user", "api-key", Currency.EUR, "sandbox");
    }

    private static StubResponse token() {
        return new StubResponse(200, StubMtn.tokenJson("tok", 3600));
    }

    // --- an unanswered check is unknown, never a guess ------------------------------

    @Test
    @DisplayName("an operator that does not answer throws — never HolderStatus.INACTIVE as a guessed default")
    void an_operator_that_does_not_answer_is_never_inactive() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(503, "{}"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            // The temptation this test exists to catch: a caller (or a future
            // implementation) treating "I could not ask" as "no, not active". The type
            // itself forbids it — INACTIVE is a real answer, not a fallback — and this is
            // the behaviour that proves the implementation honours it.
            assertThatThrownBy(() -> adapter.validateHolder(Capability.Operation.COLLECT, MSISDN))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }

    @Test
    @DisplayName("a 200 with an empty body is refused, not read as inactive")
    void an_empty_body_is_refused() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/") ? token() : new StubResponse(200, ""));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.validateHolder(Capability.Operation.COLLECT, MSISDN))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }

    @Test
    @DisplayName("a result this adapter does not recognise is refused, not read as inactive")
    void an_unrecognised_result_is_refused() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(200, "{\"result\":\"maybe\"}"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.validateHolder(Capability.Operation.COLLECT, MSISDN))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }

    // --- a real answer, either shape MTN might send -----------------------------------

    @Test
    @DisplayName("a boolean result of true is ACTIVE")
    void a_boolean_true_is_active() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(200, "{\"result\":true}"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThat(adapter.validateHolder(Capability.Operation.COLLECT, MSISDN)).isEqualTo(HolderStatus.ACTIVE);
        }
    }

    @Test
    @DisplayName("a boolean result of false is INACTIVE — a real answer, not the unanswered case")
    void a_boolean_false_is_inactive() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(200, "{\"result\":false}"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThat(adapter.validateHolder(Capability.Operation.COLLECT, MSISDN)).isEqualTo(HolderStatus.INACTIVE);
        }
    }

    @Test
    @DisplayName("a string result of \"true\" or \"false\" is read the same as a boolean — the wire shape is unconfirmed")
    void a_string_result_is_read_like_a_boolean() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(200, "{\"result\":\"true\"}"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThat(adapter.validateHolder(Capability.Operation.COLLECT, MSISDN)).isEqualTo(HolderStatus.ACTIVE);
        }
    }

    // --- routing ----------------------------------------------------------------------

    @Test
    @DisplayName("a holder check for each product hits its own path")
    void the_check_hits_the_products_own_path() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().endsWith("/token/")
                    ? token()
                    : new StubResponse(200, "{\"result\":true}"));
            MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));
            MtnDisbursementsAdapter disbursements = new MtnDisbursementsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            collections.validateHolder(Capability.Operation.COLLECT, MSISDN);
            disbursements.validateHolder(Capability.Operation.DISBURSE, MSISDN);

            assertThat(mtn.countPath("/collection/v1_0/accountholder/msisdn/" + MSISDN + "/active")).isEqualTo(1);
            assertThat(mtn.countPath("/disbursement/v1_0/accountholder/msisdn/" + MSISDN + "/active")).isEqualTo(1);
        }
    }
}
