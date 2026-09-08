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
 */
final class MtnConformanceHarness implements ConformanceHarness {

    /** Short, because the simulator lets it be. A real operator's floor would simply cost more wait. */
    private static final Duration CREDENTIAL_LIFETIME = Duration.ofSeconds(2);

    private final SimulatorUnderTest simulator;
    private final MtnCollectionsAdapter adapter;

    private String onSubmit = "\"onSubmit\":{\"outcome\":\"ACCEPT\"}";
    private String onQuery = "\"onQuery\":[{\"status\":\"SUCCESSFUL\"}]";
    private String token = "\"token\":{\"ttl\":\"PT1H\"}";

    MtnConformanceHarness(SimulatorUnderTest simulator) {
        this.simulator = simulator;
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
        return new PaymentIntent(Capability.COLLECT, Money.of(5000, Currency.EUR),
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
    public void close() {
        simulator.reset();
    }

    private void redeclare() {
        simulator.declare("{" + token + ",\"rules\":[{\"scenario\":{" + onSubmit + "," + onQuery + "}}]}");
    }
}
