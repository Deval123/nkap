package dev.nkap.provider.mtn;

import dev.nkap.conformance.ConformanceHarness;
import dev.nkap.conformance.ProviderAdapterConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The MTN adapter, run against the conformance kit. One simulator for the class; a fresh
 * harness (a fresh adapter and a clean control plane) for each rule.
 */
class MtnConformanceTest extends ProviderAdapterConformanceTest {

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

    @Override
    protected ConformanceHarness newHarness() {
        return new MtnConformanceHarness(simulator);
    }
}
