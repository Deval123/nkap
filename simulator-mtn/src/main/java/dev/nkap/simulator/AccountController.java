package dev.nkap.simulator;

import dev.nkap.simulator.scenario.AccountOutcome;
import dev.nkap.simulator.scenario.BalanceBehaviour;
import dev.nkap.simulator.scenario.HolderBehaviour;
import dev.nkap.simulator.scenario.ScenarioEngine;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * The MTN MoMo Account Balance and Account Holder surfaces — {@code GET .../account/balance}
 * and {@code GET .../accountholder/msisdn/{msisdn}/active} (issue #72). The shape answered
 * here is documented, not observed against a live sandbox — see "Still unknown" in
 * {@code docs/providers/mtn.md}.
 *
 * <p>Registered under <strong>both</strong> {@code /collection/v1_0} and
 * {@code /disbursement/v1_0}. Unlike a payment, neither read has a reference or a timeline
 * — {@link ScenarioEngine}'s per-reference resolution (ADR 0002) does not apply — so, unlike
 * {@link RequestToPayController} and {@link TransferController}, there is no per-product
 * behaviour to keep apart and one controller answers both products identically. The
 * {@code msisdn} path segment is not consulted: the simulator holds one declared holder
 * status, not a per-account ledger.
 */
@RestController
public class AccountController {

    private final ScenarioEngine engine;
    private final TokenAuthenticator authenticator;

    AccountController(ScenarioEngine engine, TokenAuthenticator authenticator) {
        this.engine = engine;
        this.authenticator = authenticator;
    }

    @GetMapping({"/collection/v1_0/account/balance", "/disbursement/v1_0/account/balance"})
    public Object balance(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticator.require(authorization);
        BalanceBehaviour behaviour = engine.account().balance();
        if (behaviour.outcome() == AccountOutcome.NO_RESPONSE) {
            return neverAnswer();
        }
        return Map.of("availableBalance", behaviour.availableBalance(), "currency", behaviour.currency());
    }

    @GetMapping({"/collection/v1_0/accountholder/msisdn/{msisdn}/active",
                "/disbursement/v1_0/accountholder/msisdn/{msisdn}/active"})
    public Object holderActive(
            @PathVariable String msisdn,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticator.require(authorization);
        HolderBehaviour behaviour = engine.account().holder();
        if (behaviour.outcome() == AccountOutcome.NO_RESPONSE) {
            return neverAnswer();
        }
        return Map.of("result", behaviour.active());
    }

    /** The {@code NO_RESPONSE} outcome — see {@link RequestToPayController#requestToPay} for why not a sleep. */
    private static DeferredResult<Object> neverAnswer() {
        return new DeferredResult<>(Duration.ofHours(1).toMillis());
    }
}
