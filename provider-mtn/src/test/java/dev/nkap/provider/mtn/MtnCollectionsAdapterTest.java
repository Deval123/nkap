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
 * What is genuinely MTN's, driven through the simulator: the happy path and the query
 * payload shape, the 404 → {@code UNKNOWN} handling, an unrecognised operator code, a 5xx,
 * the profile currency check and the callback body shape. Balance and account-holder
 * validation have their own files, {@link MtnBalanceTest} and {@link MtnHolderValidationTest}.
 *
 * <p>The rules that are <em>every</em> adapter's — a duplicate submission, a timeout, an
 * outright refusal, a flapping status, credential renewal, an untrusted callback — moved to
 * {@link MtnConformanceTest} when the conformance kit was extracted. They are not
 * duplicated here: the kit is the source of truth for them.
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

    private MtnProfile profileAt(URI base) {
        return new MtnProfile(base, "sandbox", "sub-key", "api-user", "api-key", Currency.EUR, "sandbox");
    }

    private MtnCollectionsAdapter adapter() {
        return new MtnCollectionsAdapter(profile(), Duration.ofSeconds(2));
    }

    private PaymentIntent collectIntent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "nkap test", "nkap test", Map.of());
    }

    @Test
    @DisplayName("the happy path: a submission is acknowledged, then a query reports SUCCEEDED with MTN's status code")
    void the_happy_path() throws Exception {
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult submitted = mtn.submit(collectIntent(), reference);
        assertThat(submitted).isInstanceOfSatisfying(SubmitResult.Acknowledged.class,
                acknowledged -> assertThat(acknowledged.state()).isEqualTo(PaymentState.SUBMITTED));

        ProviderStatus status = mtn.query(QuerySubject.of(reference), Capability.Operation.COLLECT);
        assertThat(status.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(status.providerStatusCode()).isEqualTo("SUCCESSFUL");
    }

    @Test
    @DisplayName("a status code the map does not know maps to UNKNOWN, not FAILED")
    void an_unknown_status_code_is_unknown() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onQuery":[{"status":"FAILED","reason":"GALACTIC_INTERFERENCE"}]}}]}""");
        MtnCollectionsAdapter mtn = adapter();
        ReferenceId reference = ReferenceId.newReference();
        mtn.submit(collectIntent(), reference);

        assertThat(mtn.query(QuerySubject.of(reference), Capability.Operation.COLLECT).state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("a query on a reference the operator has never seen is UNKNOWN, not a failure")
    void a_query_on_an_unknown_reference_is_unknown() throws Exception {
        ProviderStatus status = adapter().query(QuerySubject.of(ReferenceId.newReference()), Capability.Operation.COLLECT);

        assertThat(status.state()).isEqualTo(PaymentState.UNKNOWN);
    }

    @Test
    @DisplayName("a 500 from MTN is unavailable, never a failure")
    void a_500_on_submit_is_unavailable() throws Exception {
        simulator.declare("{\"rules\":[{\"scenario\":{\"onSubmit\":{\"outcome\":\"SERVER_ERROR\"}}}]}");

        assertThatThrownBy(() -> adapter().submit(collectIntent(), ReferenceId.newReference()))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    /**
     * Issue #28: only {@code RESOURCE_ALREADY_EXIST} on a 409 means a previous attempt
     * reached MTN. Any other code is unexpected -- the gateway does not know what happened
     * to this submission, so it is treated exactly like any other unrecognised status, never
     * as an outright refusal. {@code PaymentApiIT.a_409_with_a_different_code_is_202_unknown_and_persisted}
     * pins what a payment actually becomes when this is reached through the server; this
     * pins the adapter's own contract.
     */
    @Test
    @DisplayName("a 409 carrying a code other than RESOURCE_ALREADY_EXIST is unavailable, not already-submitted")
    void a_409_with_a_different_code_is_unavailable() throws Exception {
        simulator.declare("""
                {"rules":[{"scenario":{"onSubmit":{"outcome":"CONFLICT","code":"SOME_OTHER_CODE"}}}]}""");

        assertThatThrownBy(() -> adapter().submit(collectIntent(), ReferenceId.newReference()))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    @DisplayName("a 409 carrying RESOURCE_ALREADY_EXIST is still acknowledged, exactly as before issue #28")
    void a_409_with_resource_already_exist_is_still_acknowledged() throws Exception {
        // The default code CONFLICT carries with no override -- MtnErrorResponse.duplicateReference().
        simulator.declare("{\"rules\":[{\"scenario\":{\"onSubmit\":{\"outcome\":\"CONFLICT\"}}}]}");

        SubmitResult submitted = adapter().submit(collectIntent(), ReferenceId.newReference());

        assertThat(submitted).isInstanceOfSatisfying(SubmitResult.Acknowledged.class,
                acknowledged -> assertThat(acknowledged.state()).isEqualTo(PaymentState.SUBMITTED));
    }

    @Test
    @DisplayName("a 409 whose body cannot be read as MTN's error shape at all is unavailable, not already-submitted")
    void a_409_with_an_unreadable_body_is_unavailable() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? new StubResponse(200, StubMtn.tokenJson("tok", 3600))
                    : new StubResponse(409, "this is not JSON"));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.submit(collectIntent(), ReferenceId.newReference()))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }

    @Test
    @DisplayName("the adapter advertises the mtn id and exactly the COLLECT capability")
    void identity_and_capabilities() {
        MtnCollectionsAdapter mtn = adapter();

        assertThat(mtn.id()).isEqualTo(ProviderId.of("mtn"));
        assertThat(mtn.capabilities()).containsExactlyInAnyOrder(
                Capability.Operation.COLLECT, Capability.Feature.BALANCE, Capability.Feature.HOLDER_VALIDATION);
    }

    @Test
    @DisplayName("a payment whose currency is not the profile's is refused before any call")
    void a_currency_mismatch_is_refused() {
        PaymentIntent wrongCurrency = new PaymentIntent(Capability.Operation.COLLECT, Money.of(1000, Currency.XOF),
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

    /**
     * {@code X-Callback-Url} (issue #116): {@code intent.providerOptions().get("callbackUrl")}
     * is the only thing that puts it on the wire, and this is the one place that decides
     * whether MTN can ever call this deployment back at all. {@code server} fills that map
     * from {@code nkap.public-base-url} ({@code PublicBaseUrl}) — nothing here assumes that;
     * the adapter only ever sees a plain map.
     */
    @Test
    @DisplayName("X-Callback-Url is sent when providerOptions carries callbackUrl, and never sent when it does not")
    void the_callback_url_header_follows_provider_options() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? new StubResponse(200, StubMtn.tokenJson("tok", 3600))
                    : new StubResponse(202, ""));
            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            adapter.submit(collectIntent(), ReferenceId.newReference());
            PaymentIntent withCallback = new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
                    "46733123453", "", "", Map.of("callbackUrl", "https://gateway.example/callbacks/mtn-cm"));
            adapter.submit(withCallback, ReferenceId.newReference());

            assertThat(mtn.requests).filteredOn(r -> r.path().equals("/collection/v1_0/requesttopay"))
                    .satisfiesExactly(
                            withoutHeader -> assertThat(withoutHeader.header("X-Callback-Url")).isNull(),
                            withHeader -> assertThat(withHeader.header("X-Callback-Url"))
                                    .isEqualTo("https://gateway.example/callbacks/mtn-cm"));
        }
    }
}
