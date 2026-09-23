package dev.nkap.simulator.scenario;

import org.springframework.stereotype.Component;

/**
 * The engine, bound to MTN's scenario document: rules are {@link ScenarioRule}s, a query
 * answers with a {@link QueryBehaviour}, and a submission no rule matches plays
 * {@link Scenario#happyPath()}. Everything it decides is {@link TimelineEngine}'s.
 */
@Component
public class ScenarioEngine extends TimelineEngine<ScenarioRule, Scenario, QueryBehaviour> {

    public ScenarioEngine() {
        super(Scenario::happyPath);
    }
}
