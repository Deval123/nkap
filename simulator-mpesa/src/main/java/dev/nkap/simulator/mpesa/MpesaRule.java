package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.scenario.RequestMatcher;
import dev.nkap.simulator.scenario.Rule;

/**
 * Pairs a {@link RequestMatcher} with the {@link MpesaScenario} to play when it matches.
 * Its {@code referenceId} is matched against the submission's {@code AccountReference} — the
 * only reference the caller chooses, since Safaricom mints the payment's identity — its
 * {@code msisdn} against {@code PhoneNumber}, and its {@code amount} against {@code Amount}.
 * An STK Push carries no currency, so a matcher naming one never matches.
 */
public record MpesaRule(RequestMatcher match, MpesaScenario scenario) implements Rule<MpesaScenario> {

    public MpesaRule {
        match = match != null ? match : new RequestMatcher(null, null, null, null);
        scenario = scenario != null ? scenario : MpesaScenario.happyPath();
    }
}
