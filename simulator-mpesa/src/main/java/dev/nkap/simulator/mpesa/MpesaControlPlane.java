package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.CallbackDispatcher;
import dev.nkap.simulator.ControlPlane;
import dev.nkap.simulator.PaymentIdentity;
import dev.nkap.simulator.ReferenceStore;
import dev.nkap.simulator.SubmissionCount;
import dev.nkap.simulator.scenario.AccountBehaviour;
import dev.nkap.simulator.scenario.TokenBehaviour;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control plane, bound to M-Pesa's scenario document. Every route, and everything they
 * do, is {@link ControlPlane}'s, except {@code GET /_nkap/received}.
 *
 * <p>That one is M-Pesa's own, not the core's, because what it reports is M-Pesa's: a Consumer
 * Key and Secret, a {@code BusinessShortCode}, a {@code Password}. Another face would not mean the
 * same thing by it, so it is not a core route. The same recording could serve MTN, whose token
 * request also carries Basic credentials; that is a possibility, not built.
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

    /**
     * The body of {@code GET /_nkap/received}: how many token requests and submissions arrived
     * since {@code DELETE /_nkap/state}, and the most recent of each, null before the first.
     * The submission count is of requests that arrived, before any refusal, so it can exceed
     * {@code GET /_nkap/submissions}, which counts only what was processed.
     */
    public record ReceivedView(int tokenRequests, MpesaReceived.TokenRequest lastTokenRequest,
                               int submissionRequests, MpesaReceived.Submission lastSubmission) {}

    private final MpesaReceived received;

    MpesaControlPlane(MpesaScenarioEngine engine, ReferenceStore store, CallbackDispatcher<MpesaResult> callbacks,
                      SubmissionCount submissions, PaymentIdentity identity, MpesaReceived received) {
        super(engine, store, callbacks, submissions, identity, Declaration.class);
        this.received = received;
    }

    /** What arrived, recorded and never checked; see {@link MpesaReceived}. */
    @GetMapping("/received")
    public ReceivedView received() {
        return new ReceivedView(received.tokenRequests(), received.lastTokenRequest(), received.submissions(),
                received.lastSubmission());
    }

    /** {@code DELETE /_nkap/state} clears the record too; {@code DELETE /_nkap/scenarios} does not. */
    @Override
    protected void forgetFaceState() {
        received.clear();
    }

    @Override
    protected Declaration document(TokenBehaviour token, String callbackUrl, List<MpesaRule> rules,
                                   AccountBehaviour account) {
        return new Declaration(token, callbackUrl, rules);
    }
}
