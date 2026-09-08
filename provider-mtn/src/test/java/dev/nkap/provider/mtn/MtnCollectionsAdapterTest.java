package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adapter, driven through the scenarios the simulator already knows. No test here
 * reaches the real MTN sandbox — that is {@link MtnSandboxIT}, which is manual.
 */
class MtnCollectionsAdapterTest {

    private static SimulatorUnderTest simulator;

    @BeforeAll
    static void startSimulator() {
        simulator = new SimulatorUnderTest();
    }

    @AfterAll
    static void stopSimulator() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @BeforeEach
    void resetSimulator() {
        simulator.reset();
    }

    private MtnProfile profile() {
        return new MtnProfile(simulator.baseUrl(), "sandbox", "sub-key", "api-user", "api-key",
                Currency.EUR, "sandbox");
    }

    private MtnCollectionsAdapter adapter() {
        return new MtnCollectionsAdapter(profile(), Duration.ofSeconds(2));
    }

    private PaymentIntent collectIntent() {
        return new PaymentIntent(Capability.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "nkap test", "nkap test", Map.of());
    }

    @Test
    @DisplayName("the happy path: a submission is acknowledged, then a query reports SUCCEEDED")
    void the_happy_path() throws Exception {
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult submitted = mtn.submit(collectIntent(), reference);
        assertThat(submitted.state()).isEqualTo(PaymentState.SUBMITTED);

        ProviderStatus status = mtn.query(reference);
        assertThat(status.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(status.providerStatusCode()).isEqualTo("SUCCESSFUL");
    }

    @Test
    @DisplayName("a timeout on submit then a successful query is UNKNOWN then SUCCEEDED — the adapter never produces FAILED")
    void a_timeout_on_submit_is_never_a_failure() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}""");
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();

        assertThatThrownBy(() -> mtn.submit(collectIntent(), reference))
                .isInstanceOf(ProviderUnavailableException.class);

        // The reference reached the simulator before it went silent; the query settles it.
        ProviderStatus status = mtn.query(reference);
        assertThat(status.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(status.state()).isNotEqualTo(PaymentState.FAILED);
    }

    @Test
    @DisplayName("the same reference submitted twice is treated as already submitted, not as an error")
    void a_duplicate_submission_is_not_an_error() throws Exception {
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();

        assertThat(mtn.submit(collectIntent(), reference).state()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(mtn.submit(collectIntent(), reference).state()).isEqualTo(PaymentState.SUBMITTED);
    }

    @Test
    @DisplayName("a flapping status is reported on each query, and the adapter decides nothing")
    void a_flapping_status_is_reported_not_decided() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"},{"status":"FAILED"}]}}]}""");
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();
        mtn.submit(collectIntent(), reference);

        assertThat(mtn.query(reference).state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(mtn.query(reference).state()).isEqualTo(PaymentState.FAILED);
        assertThat(mtn.query(reference).state()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    @DisplayName("with enforcement on and a short lifetime, the adapter renews the token so calls keep working past expiry")
    void a_token_expiring_mid_flight_is_renewed() throws Exception {
        simulator.declare("{\"token\":{\"ttl\":\"PT2S\",\"enforce\":true},\"rules\":[]}");
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();
        assertThat(mtn.submit(collectIntent(), reference).state()).isEqualTo(PaymentState.SUBMITTED);

        // For six continuous seconds — three token lifetimes — every query must succeed.
        // A failure to renew would surface as a 401, i.e. ProviderUnavailableException.
        await().atMost(Duration.ofSeconds(9))
                .during(Duration.ofSeconds(6))
                .pollInterval(Duration.ofMillis(400))
                .untilAsserted(() -> assertThat(mtn.query(reference).state()).isEqualTo(PaymentState.SUCCEEDED));
    }

    @Test
    @DisplayName("a status code the map does not know maps to UNKNOWN, not FAILED")
    void an_unknown_status_code_is_unknown() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onQuery":[{"status":"FAILED","reason":"GALACTIC_INTERFERENCE"}]}}]}""");
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();
        mtn.submit(collectIntent(), reference);

        assertThat(mtn.query(reference).state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("a query on a reference the operator has never seen is UNKNOWN, not a failure")
    void a_query_on_an_unknown_reference_is_unknown() throws Exception {
        ProviderStatus status = adapter().query(ReferenceId.newReference());

        assertThat(status.state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("a 400 from MTN is a rejection, distinct from UNKNOWN and from unavailable")
    void a_400_on_submit_is_a_rejection() throws Exception {
        simulator.declare("{\"rules\":[{\"scenario\":{\"onSubmit\":{\"outcome\":\"BAD_REQUEST\"}}}]}");

        assertThatThrownBy(() -> adapter().submit(collectIntent(), ReferenceId.newReference()))
                .isInstanceOf(MtnRequestRejected.class);
    }

    @Test
    @DisplayName("a 500 from MTN is unavailable, never a failure")
    void a_500_on_submit_is_unavailable() throws Exception {
        simulator.declare("{\"rules\":[{\"scenario\":{\"onSubmit\":{\"outcome\":\"SERVER_ERROR\"}}}]}");

        assertThatThrownBy(() -> adapter().submit(collectIntent(), ReferenceId.newReference()))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    @DisplayName("the adapter advertises the mtn id and exactly the COLLECT capability")
    void identity_and_capabilities() {
        MtnCollectionsAdapter mtn = adapter();

        assertThat(mtn.id()).isEqualTo(ProviderId.of("mtn"));
        assertThat(mtn.capabilities()).containsExactly(Capability.COLLECT);
    }

    @Test
    @DisplayName("a payment whose currency is not the profile's is refused before any call")
    void a_currency_mismatch_is_refused() {
        PaymentIntent wrongCurrency = new PaymentIntent(Capability.COLLECT, Money.of(1000, Currency.XOF),
                "46733123453", "", "", Map.of());

        assertThatThrownBy(() -> adapter().submit(wrongCurrency, ReferenceId.newReference()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XOF");
    }

    @Test
    @DisplayName("a well-formed callback is parsed into its reference and mapped status")
    void a_callback_is_parsed() throws Exception {
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();
        String body = "{\"referenceId\":\"" + reference + "\",\"status\":\"SUCCESSFUL\","
                + "\"amount\":\"50.00\",\"currency\":\"EUR\"}";

        CallbackEvent event = mtn.parseCallback(new RawCallback(Map.of(), body));

        assertThat(event.reference()).isEqualTo(reference);
        assertThat(event.status().state()).isEqualTo(PaymentState.SUCCEEDED);
    }

    @Test
    @DisplayName("a callback that is not JSON, or carries no reference, is rejected as untrusted")
    void a_bad_callback_is_rejected() {
        MtnCollectionsAdapter mtn = adapter();

        assertThatThrownBy(() -> mtn.parseCallback(new RawCallback(Map.of(), "definitely not json")))
                .isInstanceOf(UntrustedCallbackException.class);
        assertThatThrownBy(() -> mtn.parseCallback(new RawCallback(Map.of(), "{\"status\":\"SUCCESSFUL\"}")))
                .isInstanceOf(UntrustedCallbackException.class);
    }

    @Test
    @DisplayName("balance is not offered by the collections adapter")
    void balance_is_unsupported() {
        assertThatThrownBy(() -> adapter().balance(Currency.EUR))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
