package dev.nkap.provider.mpesa;

import dev.nkap.conformance.CallbackReceiver;
import dev.nkap.conformance.ConformanceHarness;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.RawCallback;
import dev.nkap.testsupport.SimulatorUnderTest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Drives the M-Pesa adapter through {@code simulator-mpesa}'s control plane, one condition at
 * a time, the way {@code provider-mtn}'s harness drives MTN's face: the three dimensions it
 * cares about — the submit outcome, the query outcome, the token — held as clauses and
 * re-declared together whenever one changes.
 *
 * <p>Every declaration also asks the operator to call back, {@code after: PT0S}, reporting
 * success. Unlike MTN's harness, the address is not declared in the control plane: M-Pesa
 * takes it from the submission's own {@code CallBackURL}, and the adapter fills that from
 * {@link PaymentIntent#providerOptions()} — so {@link #anIntent()} carries this harness's
 * private route, the way the gateway would carry its per-payment address.
 */
final class MpesaConformanceHarness implements ConformanceHarness {

    /** Short, because the simulator lets it be. */
    private static final Duration CREDENTIAL_LIFETIME = Duration.ofSeconds(2);

    private final SimulatorUnderTest simulator;
    private final CallbackReceiver.Route route;
    private final MpesaAdapter adapter;

    private String onSubmit = "\"onSubmit\":{\"outcome\":\"ACCEPT\"}";
    private String onQuery = "\"onQuery\":[{\"status\":\"SUCCESS\"}]";
    private String token = "\"token\":{\"ttl\":\"PT1H\"}";

    MpesaConformanceHarness(SimulatorUnderTest simulator, CallbackReceiver callbacks) {
        this.simulator = simulator;
        this.route = callbacks.open();
        MpesaProfile profile = new MpesaProfile(simulator.baseUrl(), "174379", "passkey", "consumer-key",
                "consumer-secret", Currency.KES);
        this.adapter = new MpesaAdapter(ProviderId.of("mpesa"), profile, Duration.ofSeconds(2),
                MpesaTokenCache.DEFAULT_REFRESH_MARGIN, Clock.systemUTC());
        redeclare();
    }

    @Override
    public ProviderAdapter adapter() {
        return adapter;
    }

    /** One shilling — 100 minor units of KES — as the observed runs submitted. */
    @Override
    public PaymentIntent anIntent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(100, Currency.KES),
                "254708374149", "nkap conformance", "nkap conformance", Map.of("callbackUrl", route.url()));
    }

    @Override
    public void makeSubmitNeverAnswer() {
        onSubmit = "\"onSubmit\":{\"outcome\":\"NO_RESPONSE\"}";
        redeclare();
    }

    @Override
    public void makeSubmitRejected() {
        onSubmit = "\"onSubmit\":{\"outcome\":\"BAD_REQUEST\"}";
        redeclare();
    }

    @Override
    public void makeNextQuerySucceed() {
        onQuery = "\"onQuery\":[{\"status\":\"SUCCESS\"}]";
        redeclare();
    }

    @Override
    public void makeStatusFlap() {
        onQuery = "\"onQuery\":[{\"status\":\"SUCCESS\"},{\"status\":\"NO_RESPONSE_FROM_USER\"}]";
        redeclare();
    }

    /** A ResultCode nobody has observed: the face's test-only code, labelled so on the wire. */
    @Override
    public void makeStatusUnrecognised() {
        onQuery = "\"onQuery\":[{\"resultCode\":987654}]";
        redeclare();
    }

    @Override
    public void expireCredentialsMidFlight() {
        token = "\"token\":{\"ttl\":\"" + CREDENTIAL_LIFETIME + "\",\"enforce\":true}";
        redeclare();
    }

    @Override
    public Duration credentialLifetime() {
        return CREDENTIAL_LIFETIME;
    }

    /** An STK callback naming no CheckoutRequestID: nothing to attribute it with. */
    @Override
    public RawCallback anUntrustedCallback() {
        return new RawCallback(Map.of(), "{\"Body\":{\"stkCallback\":{\"ResultCode\":0}}}");
    }

    @Override
    public RawCallback aDeliveredCallback() {
        return route.poll(Duration.ofSeconds(5));
    }

    @Override
    public int submissionsReceived() {
        return simulator.submissionsReceived();
    }

    @Override
    public void close() {
        simulator.reset();
        route.close();
    }

    private void redeclare() {
        simulator.declare("{" + token + ",\"rules\":[{\"scenario\":{" + onSubmit + "," + onQuery
                + ",\"callbacks\":[{\"after\":\"PT0S\",\"status\":\"SUCCESS\"}]}}]}");
    }
}
