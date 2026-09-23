package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.scenario.TimelineEngine;
import org.springframework.stereotype.Component;

/**
 * The engine, bound to M-Pesa's scenario document. Everything it decides is
 * {@link TimelineEngine}'s.
 */
@Component
public class MpesaScenarioEngine extends TimelineEngine<MpesaRule, MpesaScenario, MpesaQueryBehaviour> {

    public MpesaScenarioEngine() {
        super(MpesaScenario::happyPath);
    }
}
