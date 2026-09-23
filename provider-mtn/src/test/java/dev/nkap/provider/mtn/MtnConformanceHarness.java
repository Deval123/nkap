package dev.nkap.provider.mtn;

import dev.nkap.conformance.ConformanceHarness;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.RawCallback;
import java.time.Duration;
import java.util.Map;

/**
 * Drives the MTN adapter through the simulator's control plane, one condition at a time.
 *
 * <p>The simulator replaces its whole configuration on every {@code POST /_nkap/scenarios},
 * so the harness holds the three dimensions it cares about — the submit outcome, the query
 * outcome, the token — as clauses and re-declares the lot whenever one changes. That is the
 * only real awkwardness in writing one of these.
 *
 * <p>{@link #aDeliveredCallback()} needs a fourth thing: an address of its own. Every
 * declaration therefore also tells the simulator to call back — {@code after: PT0S},
 * {@code SUCCESSFUL} — to {@code route}, this harness's own private, isolated path on a real
 * HTTP receiver shared across the whole test class (see {@link CallbackReceiver}'s own javadoc
 * for why the receiver itself is not started fresh per harness, and why a route is not shared),
 * so that whichever submission the kit happens to be exercising, the callback the operator
 * sends for it lands somewhere real, belonging to no other harness, and is handed back exactly
 * as it arrived.
 */
final class MtnConformanceHarness implements ConformanceHarness {

    /** Short, because the simulator lets it be. A real operator's floor would simply cost more wait. */
    private static final Duration CREDENTIAL_LIFETIME = Duration.ofSeconds(2);

    private final SimulatorUnderTest simulator;
    private final CallbackReceiver.Route route;
    private final MtnCollectionsAdapter adapter;

    private String onSubmit = "\"onSubmit\":{\"outcome\":\"ACCEPT\"}";
    private String onQuery = "\"onQuery\":[{\"status\":\"SUCCESSFUL\"}]";
    private String token = "\"token\":{\"ttl\":\"PT1H\"}";

    MtnConformanceHarness(SimulatorUnderTest simulator, CallbackReceiver callbacks) {
        this.simulator = simulator;
        this.route = callbacks.open();
        MtnProfile profile = new MtnProfile(simulator.baseUrl(), "sandbox", "sub-key", "api-user", "api-key",
                Currency.EUR, "sandbox");
        this.adapter = new MtnCollectionsAdapter(profile, Duration.ofSeconds(2));
        redeclare();
    }

    @Override
    public ProviderAdapter adapter() {
        return adapter;
    }

    @Override
    public PaymentIntent anIntent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(5000, Currency.EUR),
                "46733123453", "nkap conformance", "nkap conformance", Map.of());
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
        onQuery = "\"onQuery\":[{\"status\":\"SUCCESSFUL\"}]";
        redeclare();
    }

    @Override
    public void makeStatusFlap() {
        onQuery = "\"onQuery\":[{\"status\":\"SUCCESSFUL\"},{\"status\":\"FAILED\"}]";
        redeclare();
    }

    @Override
    public void makeStatusUnrecognised() {
        // status stays a valid MomoStatus — the simulator only accepts the enum — but reason
        // is free text (QueryBehaviour.reason), unvalidated, which is exactly how an operator
        // code MtnStatusMap has never seen reaches the adapter. An unrecognised reason poisons
        // the whole read to UNKNOWN even though status is a code this table does recognise
        // (MtnStatusMap#stateFor) — a code this adapter has never seen is not license to
        // trust the field it has.
        onQuery = "\"onQuery\":[{\"status\":\"FAILED\",\"reason\":\"SOMETHING_NOBODY_MAPS\"}]";
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

    @Override
    public RawCallback anUntrustedCallback() {
        // A body MTN's parseCallback cannot tie to a payment: a status, but no reference.
        return new RawCallback(Map.of(), "{\"status\":\"SUCCESSFUL\"}");
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
        simulator.declare("{" + token + ",\"callbackUrl\":\"" + route.url() + "\","
                + "\"rules\":[{\"scenario\":{" + onSubmit + "," + onQuery
                + ",\"callbacks\":[{\"after\":\"PT0S\",\"status\":\"SUCCESSFUL\"}]}}]}");
    }
}
