package dev.nkap.provider.mtn;

import dev.nkap.conformance.CallbackReceiver;
import dev.nkap.conformance.ConformanceHarness;
import dev.nkap.conformance.ProviderAdapterConformanceTest;
import dev.nkap.simulator.SimulatorApplication;
import dev.nkap.testsupport.SimulatorUnderTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The MTN adapter, run against the conformance kit. One simulator and one callback receiver
 * for the class — see {@link CallbackReceiver}'s own javadoc for why the receiver is not
 * started fresh per rule the way the rest of the harness is — and a fresh harness (a fresh
 * adapter and a clean control plane) for each rule.
 */
class MtnConformanceTest extends ProviderAdapterConformanceTest {

    private static SimulatorUnderTest simulator;
    private static CallbackReceiver callbacks;

    @BeforeAll
    static void startSimulator() {
        simulator = new SimulatorUnderTest(SimulatorApplication.class, "simulator (MTN)");
        callbacks = new CallbackReceiver();
    }

    @AfterAll
    static void stopSimulator() {
        if (callbacks != null) {
            callbacks.close();
        }
        if (simulator != null) {
            simulator.close();
        }
    }

    @Override
    protected ConformanceHarness newHarness() {
        return new MtnConformanceHarness(simulator, callbacks);
    }
}
