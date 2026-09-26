package dev.nkap.provider.mpesa;

import dev.nkap.conformance.CallbackReceiver;
import dev.nkap.conformance.ConformanceHarness;
import dev.nkap.conformance.ProviderAdapterConformanceTest;
import dev.nkap.simulator.mpesa.app.MpesaSimulatorApplication;
import dev.nkap.testsupport.SimulatorUnderTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The M-Pesa adapter, run against every rule of the conformance kit: one simulator and one
 * callback receiver for the class, a fresh harness for each rule.
 */
class MpesaConformanceTest extends ProviderAdapterConformanceTest {

    private static SimulatorUnderTest simulator;
    private static CallbackReceiver callbacks;

    @BeforeAll
    static void startSimulator() {
        simulator = new SimulatorUnderTest(MpesaSimulatorApplication.class, "simulator-mpesa");
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
        return new MpesaConformanceHarness(simulator, callbacks);
    }
}
