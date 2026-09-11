package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.SubmitResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The disbursements adapter against the simulator's {@code /disbursement/v1_0/transfer}
 * product — the mirror of {@link MtnCollectionsAdapterTest}. Same conservatism: an
 * unrecognised code is {@code UNKNOWN}, and a transfer MTN refuses for lack of funds is an
 * ordinary {@code FAILED} with the operator's code, not something predicted.
 */
class MtnDisbursementsAdapterTest {

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

    private MtnDisbursementsAdapter adapter() {
        MtnProfile profile = new MtnProfile(simulator.baseUrl(), "sandbox", "disb-sub-key", "disb-user", "disb-key",
                Currency.EUR, "sandbox");
        return new MtnDisbursementsAdapter(profile, Duration.ofSeconds(2));
    }

    private PaymentIntent disburseIntent() {
        return new PaymentIntent(Capability.DISBURSE, Money.of(5000, Currency.EUR),
                "46733123453", "nkap test", "nkap test", Map.of());
    }

    @Test
    @DisplayName("the happy path: a transfer is acknowledged, then a query reports SUCCEEDED with MTN's status code")
    void the_happy_path() throws Exception {
        MtnDisbursementsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult submitted = mtn.submit(disburseIntent(), reference);
        assertThat(submitted).isInstanceOfSatisfying(SubmitResult.Acknowledged.class,
                acknowledged -> assertThat(acknowledged.state()).isEqualTo(PaymentState.SUBMITTED));

        ProviderStatus status = mtn.query(reference, Capability.DISBURSE);
        assertThat(status.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(status.providerStatusCode()).isEqualTo("SUCCESSFUL");
    }

    @Test
    @DisplayName("a transfer refused for insufficient funds is FAILED with the operator's code, not a prediction")
    void insufficient_funds_is_failed_with_the_operators_code() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onQuery":[{"status":"FAILED","reason":"NOT_ENOUGH_FUNDS"}]}}]}""");
        MtnDisbursementsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();
        mtn.submit(disburseIntent(), reference);

        ProviderStatus status = mtn.query(reference, Capability.DISBURSE);
        assertThat(status.state()).isEqualTo(PaymentState.FAILED);
        assertThat(status.providerStatusCode()).isEqualTo("NOT_ENOUGH_FUNDS");
    }

    @Test
    @DisplayName("a status code the map does not know maps to UNKNOWN, not FAILED")
    void an_unknown_status_code_is_unknown() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onQuery":[{"status":"FAILED","reason":"GALACTIC_INTERFERENCE"}]}}]}""");
        MtnDisbursementsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();
        mtn.submit(disburseIntent(), reference);

        assertThat(mtn.query(reference, Capability.DISBURSE).state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("a query on a reference the operator has never seen is UNKNOWN, not a failure")
    void a_query_on_an_unknown_reference_is_unknown() throws Exception {
        assertThat(adapter().query(ReferenceId.newReference(), Capability.DISBURSE).state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("the adapter advertises the mtn id and exactly the DISBURSE capability")
    void identity_and_capabilities() {
        MtnDisbursementsAdapter mtn = adapter();
        assertThat(mtn.id()).isEqualTo(ProviderId.of("mtn"));
        assertThat(mtn.capabilities()).containsExactly(Capability.DISBURSE);
    }

    @Test
    @DisplayName("a COLLECT intent is refused by the disbursements adapter before any call")
    void a_collect_intent_is_refused() {
        PaymentIntent collect = new PaymentIntent(Capability.COLLECT, Money.of(1000, Currency.EUR),
                "46733123453", "", "", Map.of());
        assertThatThrownBy(() -> adapter().submit(collect, ReferenceId.newReference()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISBURSE");
    }

    @Test
    @DisplayName("a well-formed disbursement callback is parsed into its reference and mapped status")
    void a_callback_is_parsed() throws Exception {
        ReferenceId reference = ReferenceId.newReference();
        String body = "{\"referenceId\":\"" + reference + "\",\"status\":\"SUCCESSFUL\"}";

        CallbackEvent event = adapter().parseCallback(new RawCallback(Map.of(), body));

        assertThat(event.reference()).isEqualTo(reference);
        assertThat(event.status().state()).isEqualTo(PaymentState.SUCCEEDED);
    }
}
