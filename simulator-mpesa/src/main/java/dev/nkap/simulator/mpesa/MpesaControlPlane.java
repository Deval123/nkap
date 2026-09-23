package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.CallbackDispatcher;
import dev.nkap.simulator.ControlPlane;
import dev.nkap.simulator.PaymentIdentity;
import dev.nkap.simulator.ReferenceStore;
import dev.nkap.simulator.SubmissionCount;
import dev.nkap.simulator.scenario.AccountBehaviour;
import dev.nkap.simulator.scenario.TokenBehaviour;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control plane, bound to M-Pesa's scenario document. Every route, and everything they
 * do, is {@link ControlPlane}'s.
 */
@RestController
public class MpesaControlPlane extends ControlPlane<MpesaControlPlane.Declaration, MpesaRule> {

    /**
     * The request and response body of {@code /_nkap/scenarios} for this face: the token
     * lifetime, the fallback callback URL and the rule list. All optional.
     *
     * <p>No {@code account}: this face plays no balance or account-holder read, so its
     * document does not offer to declare one. {@link #account()} answers the core's default,
     * which nothing here reads.
     */
    public record Declaration(TokenBehaviour token, String callbackUrl, List<MpesaRule> rules)
            implements ControlPlane.Declared<MpesaRule> {

        public Declaration {
            token = token != null ? token : new TokenBehaviour(null);
            rules = rules != null ? List.copyOf(rules) : List.of();
        }

        @Override
        public AccountBehaviour account() {
            return AccountBehaviour.defaultBehaviour();
        }
    }

    MpesaControlPlane(MpesaScenarioEngine engine, ReferenceStore store, CallbackDispatcher<MpesaResult> callbacks,
                      SubmissionCount submissions, PaymentIdentity identity) {
        super(engine, store, callbacks, submissions, identity, Declaration.class);
    }

    @Override
    protected Declaration document(TokenBehaviour token, String callbackUrl, List<MpesaRule> rules,
                                   AccountBehaviour account) {
        return new Declaration(token, callbackUrl, rules);
    }
}
