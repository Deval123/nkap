package dev.nkap.simulator;

import dev.nkap.simulator.scenario.AccountBehaviour;
import dev.nkap.simulator.scenario.MomoStatus;
import dev.nkap.simulator.scenario.ScenarioEngine;
import dev.nkap.simulator.scenario.ScenarioRule;
import dev.nkap.simulator.scenario.TokenBehaviour;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control plane, bound to MTN's scenario document. Every route, and everything they
 * do, is {@link ControlPlane}'s; what is MTN's is only the vocabulary the rules in a
 * {@link Declaration} are written in.
 */
@RestController
public class ControlPlaneController extends ControlPlane<ControlPlaneController.Declaration, ScenarioRule> {

    /**
     * The request and response body of {@code /_nkap/scenarios}: the whole
     * declared configuration in one document — the token lifetime, the fallback
     * callback URL, the rule list, and the account balance / holder-validation
     * answers (issue #72). All optional; {@code token} defaults to one hour,
     * {@code rules} to empty, {@code callbackUrl} to none, {@code account} to the
     * default balance and an active holder. Posting it replaces the lot atomically.
     */
    public record Declaration(TokenBehaviour token, String callbackUrl, List<ScenarioRule> rules, AccountBehaviour account)
            implements ControlPlane.Declared<ScenarioRule> {
        public Declaration {
            token = token != null ? token : new TokenBehaviour(null);
            rules = rules != null ? List.copyOf(rules) : List.of();
            account = account != null ? account : AccountBehaviour.defaultBehaviour();
        }
    }

    ControlPlaneController(ScenarioEngine engine, ReferenceStore store, CallbackDispatcher<MomoStatus> callbacks,
                           SubmissionCount submissions, PaymentIdentity identity) {
        super(engine, store, callbacks, submissions, identity, Declaration.class);
    }

    @Override
    protected Declaration document(TokenBehaviour token, String callbackUrl, List<ScenarioRule> rules,
                                   AccountBehaviour account) {
        return new Declaration(token, callbackUrl, rules, account);
    }
}
