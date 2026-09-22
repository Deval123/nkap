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
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.Resolution;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.mtn.StubMtn.StubResponse;
import java.net.URI;
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
        return new MtnDisbursementsAdapter(profile(), Duration.ofSeconds(2));
    }

    private MtnProfile profile() {
        return new MtnProfile(simulator.baseUrl(), "sandbox", "disb-sub-key", "disb-user", "disb-key",
                Currency.EUR, "sandbox");
    }

    private MtnProfile profileAt(URI base) {
        return new MtnProfile(base, "sandbox", "disb-sub-key", "disb-user", "disb-key", Currency.EUR, "sandbox");
    }

    private PaymentIntent disburseIntent() {
        return new PaymentIntent(Capability.Operation.DISBURSE, Money.of(5000, Currency.EUR),
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

        ProviderStatus status = mtn.query(QuerySubject.of(reference), Capability.Operation.DISBURSE);
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

        ProviderStatus status = mtn.query(QuerySubject.of(reference), Capability.Operation.DISBURSE);
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

        assertThat(mtn.query(QuerySubject.of(reference), Capability.Operation.DISBURSE).state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("a query on a reference the operator has never seen is UNKNOWN, not a failure")
    void a_query_on_an_unknown_reference_is_unknown() throws Exception {
        assertThat(adapter().query(QuerySubject.of(ReferenceId.newReference()), Capability.Operation.DISBURSE).state()).isEqualTo(PaymentState.UNKNOWN);
    }

    /**
     * Issue #171, the mirror of #28: only {@code RESOURCE_ALREADY_EXIST} on a 409 means a
     * previous transfer attempt reached MTN. {@link MtnCollectionsAdapterTest}'s three 409
     * tests pin the same rule for collections; this pins it for disbursements, the more
     * expensive of the two products to get wrong, since a wrong "already submitted" here
     * means money that was never sent looks sent.
     */
    @Test
    @DisplayName("a 409 carrying a code other than RESOURCE_ALREADY_EXIST is unavailable, not already-submitted")
    void a_409_with_a_different_code_is_unavailable() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onSubmit":{"outcome":"CONFLICT","code":"SOME_OTHER_CODE"}}}]}""");

        assertThatThrownBy(() -> adapter().submit(disburseIntent(), ReferenceId.newReference()))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    @DisplayName("a 409 carrying RESOURCE_ALREADY_EXIST is still acknowledged, exactly as before issue #171")
    void a_409_with_resource_already_exist_is_still_acknowledged() throws Exception {
        // The default code CONFLICT carries with no override -- MtnErrorResponse.duplicateReference().
        simulator.declare("{\"rules\":[{\"scenario\":{\"onSubmit\":{\"outcome\":\"CONFLICT\"}}}]}");

        SubmitResult submitted = adapter().submit(disburseIntent(), ReferenceId.newReference());

        assertThat(submitted).isInstanceOfSatisfying(SubmitResult.Acknowledged.class,
                acknowledged -> assertThat(acknowledged.state()).isEqualTo(PaymentState.SUBMITTED));
    }

    @Test
    @DisplayName("a 409 whose body cannot be read as MTN's error shape at all is unavailable, not already-submitted")
    void a_409_with_an_unreadable_body_is_unavailable() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals(MtnDisbursementsAdapter.TOKEN_PATH)
                    ? new StubResponse(200, StubMtn.tokenJson("tok", 3600))
                    : new StubResponse(409, "this is not JSON"));
            MtnDisbursementsAdapter adapter = new MtnDisbursementsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.submit(disburseIntent(), ReferenceId.newReference()))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }

    @Test
    @DisplayName("the adapter advertises the mtn id and exactly the DISBURSE capability")
    void identity_and_capabilities() {
        MtnDisbursementsAdapter mtn = adapter();
        assertThat(mtn.id()).isEqualTo(ProviderId.of("mtn"));
        assertThat(mtn.capabilities()).containsExactlyInAnyOrder(
                Capability.Operation.DISBURSE, Capability.Feature.BALANCE, Capability.Feature.HOLDER_VALIDATION);
        assertThat(mtn.resolves()).containsExactlyInAnyOrder(Resolution.QUERY, Resolution.CALLBACK);
    }

    @Test
    @DisplayName("a COLLECT intent is refused by the disbursements adapter before any call")
    void a_collect_intent_is_refused() {
        PaymentIntent collect = new PaymentIntent(Capability.Operation.COLLECT, Money.of(1000, Currency.EUR),
                "46733123453", "", "", Map.of());
        assertThatThrownBy(() -> adapter().submit(collect, ReferenceId.newReference()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISBURSE");
    }

    /**
     * ADR 0013, the mirror of {@link MtnCollectionsAdapterTest#a_currency_mismatch_is_not_attempted}:
     * both adapters changed identically for the currency guard, so both are pinned identically.
     * Issue #171 existed because these two drifted once before — this is the same asserted twice,
     * not two different rules. The simulator starts from its happy-path default, where a submitted
     * transfer is accepted with {@code 202}; querying the same reference as {@code UNKNOWN} therefore
     * proves the transfer endpoint was never called, because an accepted reference remains queryable.
     */
    @Test
    @DisplayName("a payment whose currency is not the profile's is not attempted, and nothing is submitted under its reference")
    void a_currency_mismatch_is_not_attempted() throws Exception {
        PaymentIntent wrongCurrency = new PaymentIntent(Capability.Operation.DISBURSE, Money.of(1000, Currency.XOF),
                "46733123453", "", "", Map.of());
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult result = adapter().submit(wrongCurrency, reference);

        assertThat(result).isInstanceOfSatisfying(SubmitResult.NotAttempted.class,
                notAttempted -> assertThat(notAttempted.reason()).contains("XOF"));
        assertThat(adapter().query(QuerySubject.of(reference), Capability.Operation.DISBURSE).state())
                .isEqualTo(PaymentState.UNKNOWN);
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
