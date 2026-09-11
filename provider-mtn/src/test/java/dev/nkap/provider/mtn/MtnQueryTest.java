package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.mtn.StubMtn.StubResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fields the simulator does not exercise, driven through a stub: MTN's
 * {@code financialTransactionId} (present only once settled) and an MTN-shaped
 * {@code 400} body whose {@code code} and {@code message} a {@code Rejected} must carry.
 */
class MtnQueryTest {

    private MtnProfile profileAt(URI base) {
        return new MtnProfile(base, "sandbox", "sub-key", "api-user", "api-key", Currency.EUR, "sandbox");
    }

    private PaymentIntent intent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR), "46733123453", "", "", Map.of());
    }

    private static StubResponse token() {
        return new StubResponse(200, StubMtn.tokenJson("tok", 3600));
    }

    @Test
    @DisplayName("a settled query response exposes its financialTransactionId as the transaction id")
    void a_settled_payment_carries_its_transaction_id() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(200, """
                        {"status":"SUCCESSFUL","financialTransactionId":"1510430965",
                         "amount":"50.00","currency":"EUR"}"""));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            ProviderStatus status = adapter.query(ReferenceId.newReference(), Capability.Operation.COLLECT);

            assertThat(status.state()).isEqualTo(PaymentState.SUCCEEDED);
            assertThat(status.transactionId()).contains("1510430965");
        }
    }

    @Test
    @DisplayName("a pending query response carries no transaction id — it does not exist yet")
    void a_pending_payment_carries_no_transaction_id() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(200, """
                        {"externalId":"probe-1","amount":"50.00","currency":"EUR",
                         "payer":{"partyIdType":"MSISDN","partyId":"46733123453"},"status":"PENDING"}"""));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            ProviderStatus status = adapter.query(ReferenceId.newReference(), Capability.Operation.COLLECT);

            assertThat(status.state()).isEqualTo(PaymentState.PENDING);
            assertThat(status.transactionId()).isEmpty();
        }
    }

    @Test
    @DisplayName("an MTN-shaped 400 becomes a Rejected carrying the operator's code and message")
    void a_400_carries_the_operator_code_and_message() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? token()
                    : new StubResponse(400, "{\"code\":\"INVALID_CURRENCY\",\"message\":\"Currency FOO is not supported\"}"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            SubmitResult result = adapter.submit(intent(), ReferenceId.newReference());

            assertThat(result).isInstanceOfSatisfying(SubmitResult.Rejected.class, rejected -> {
                assertThat(rejected.providerCode()).isEqualTo("INVALID_CURRENCY");
                assertThat(rejected.reason()).contains("Currency FOO is not supported");
                assertThat(rejected.rawResponse()).contains("INVALID_CURRENCY");
            });
        }
    }
}
