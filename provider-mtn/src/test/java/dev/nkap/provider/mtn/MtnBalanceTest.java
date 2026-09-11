package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.provider.Capability;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.mtn.StubMtn.StubResponse;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code balance()}, driven through a stub so the exact decimal in MTN's response is
 * under this test's control (issue #72). The rejection is written first, per the plan:
 * a value with more decimal places than the currency allows is an error, not a rounding.
 */
class MtnBalanceTest {

    private MtnProfile profileAt(URI base) {
        return new MtnProfile(base, "sandbox", "sub-key", "api-user", "api-key", Currency.EUR, "sandbox");
    }

    private static StubResponse token() {
        return new StubResponse(200, StubMtn.tokenJson("tok", 3600));
    }

    private static StubResponse balance(String amount, String currency) {
        return new StubResponse(200, "{\"availableBalance\":\"" + amount + "\",\"currency\":\"" + currency + "\"}");
    }

    // --- the refusal, written first ------------------------------------------------

    @Test
    @DisplayName("a balance with more decimal places than the currency allows is an error, not a rounding")
    void extra_precision_is_refused_not_rounded() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/") ? token() : balance("50.105", "EUR"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            // EUR has two decimal places. 50.105 cannot become 5010 or 5011 minor units
            // without a guess, so this must throw — never round either way.
            assertThatThrownBy(() -> adapter.balance(Capability.Operation.COLLECT, Currency.EUR))
                    .isInstanceOf(ProviderUnavailableException.class)
                    .hasMessageContaining("50.105");
        }
    }

    @Test
    @DisplayName("a trailing zero beyond the currency's own precision is not an error — it carries no information")
    void a_harmless_trailing_zero_is_accepted() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            // XAF has zero decimal places. "1000.00" is exactly 1000 minor units; the two
            // trailing zeros are not extra precision, they are none at all.
            mtn.respondWith(request -> request.path().equals("/collection/token/") ? token() : balance("1000.00", "XAF"));
            MtnProfile xaf = new MtnProfile(mtn.baseUrl(), "sandbox", "sub-key", "api-user", "api-key", Currency.XAF, "sandbox");
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(xaf, Duration.ofSeconds(3));

            Money result = adapter.balance(Capability.Operation.COLLECT, Currency.XAF);

            assertThat(result).isEqualTo(Money.of(1000, Currency.XAF));
        }
    }

    @Test
    @DisplayName("an amount that is not a decimal number at all is refused")
    void a_garbage_amount_is_refused() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/") ? token() : balance("not-a-number", "EUR"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.balance(Capability.Operation.COLLECT, Currency.EUR))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }

    // --- exact conversion -----------------------------------------------------------

    @Test
    @DisplayName("a balance for each product hits its own path")
    void balance_hits_the_products_own_path() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().endsWith("/token/") ? token() : balance("50.00", "EUR"));
            MtnCollectionsAdapter collections = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));
            MtnDisbursementsAdapter disbursements = new MtnDisbursementsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            collections.balance(Capability.Operation.COLLECT, Currency.EUR);
            disbursements.balance(Capability.Operation.DISBURSE, Currency.EUR);

            assertThat(mtn.countPath("/collection/v1_0/account/balance")).isEqualTo(1);
            assertThat(mtn.countPath("/disbursement/v1_0/account/balance")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a two-decimal currency converts exactly: 50.00 is 5000 minor units")
    void a_two_decimal_currency_converts_exactly() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/") ? token() : balance("1234.56", "EUR"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            Money result = adapter.balance(Capability.Operation.COLLECT, Currency.EUR);

            assertThat(result).isEqualTo(Money.of(123456, Currency.EUR));
        }
    }

    // --- currency mismatch ------------------------------------------------------------

    @Test
    @DisplayName("asking in a currency this profile does not settle in is refused before any call")
    void asking_in_the_wrong_currency_is_refused_before_any_call() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.balance(Capability.Operation.COLLECT, Currency.XOF))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(mtn.requests).isEmpty();
        }
    }

    @Test
    @DisplayName("an operator that reports a different currency than asked for is refused, not coerced")
    void an_operator_reported_currency_mismatch_is_refused() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            // The profile settles in EUR (so the request passes the precondition above), but
            // MTN's own response names a different currency — not our balance.
            mtn.respondWith(request -> request.path().equals("/collection/token/") ? token() : balance("50.00", "USD"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.balance(Capability.Operation.COLLECT, Currency.EUR))
                    .isInstanceOf(ProviderUnavailableException.class)
                    .hasMessageContaining("USD");
        }
    }

    // --- no answer is unknown, never zero ----------------------------------------------

    @Test
    @DisplayName("an operator that does not answer yields ProviderUnavailableException — never a zero balance")
    void an_operator_that_does_not_answer_is_never_a_zero_balance() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(503, "{}"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            // The vehicle is the same "I do not know" every other read on the contract
            // uses — a checked exception the caller cannot mistake for a value, let alone
            // the value zero.
            assertThatThrownBy(() -> adapter.balance(Capability.Operation.COLLECT, Currency.EUR))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }

    @Test
    @DisplayName("a 200 with an empty body is refused, not read as a zero balance")
    void an_empty_body_is_refused() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/") ? token() : new StubResponse(200, ""));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.balance(Capability.Operation.COLLECT, Currency.EUR))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }
}
