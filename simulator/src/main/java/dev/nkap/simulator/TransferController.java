package dev.nkap.simulator;

import dev.nkap.simulator.scenario.QueryBehaviour;
import dev.nkap.simulator.scenario.Scenario;
import dev.nkap.simulator.scenario.ScenarioEngine;
import dev.nkap.simulator.scenario.SubmitBehaviour;
import dev.nkap.simulator.scenario.SubmitOutcome;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * The MTN MoMo Disbursements {@code transfer} surface — the mirror of
 * {@link RequestToPayController}, on {@code /disbursement/v1_0/transfer}. Same protocol
 * rules (a client-supplied {@code X-Reference-Id} UUID that is the idempotency key, a 202
 * with an empty body, a status GET, a 404 for an unknown reference), same
 * {@link ScenarioEngine} driving behaviour (ADR 0002). The only shape difference is that a
 * transfer body names the counterparty {@code payee}, where a collection says {@code payer}.
 *
 * <p>It is a parallel controller rather than a refactor of the collections one on purpose:
 * that class carries a comment about a deliberate schedule-then-sleep ordering that a shared
 * base would put at risk for a test fixture's sake. The duplication is contained here.
 */
@RestController
public class TransferController {

    private final CollectionRequestStore store;
    private final ScenarioEngine engine;
    private final CallbackDispatcher callbacks;
    private final TokenAuthenticator authenticator;

    TransferController(CollectionRequestStore store, ScenarioEngine engine,
                       CallbackDispatcher callbacks, TokenAuthenticator authenticator) {
        this.store = store;
        this.engine = engine;
        this.callbacks = callbacks;
        this.authenticator = authenticator;
    }

    @PostMapping("/disbursement/v1_0/transfer")
    public Object transfer(
            @RequestHeader(value = "X-Reference-Id", required = false) String referenceId,
            @RequestHeader(value = "X-Callback-Url", required = false) String callbackUrl,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {

        String reference = requireUuid(referenceId);
        authenticator.require(authorization);

        String msisdn = payeeId(body);
        String amount = field(body, "amount");
        String currency = field(body, "currency");

        if (!store.record(reference)) {
            throw new MtnErrorException(HttpStatus.CONFLICT, MtnErrorResponse.duplicateReference());
        }

        Scenario scenario = engine.resolveForSubmission(reference, msisdn, amount, currency);

        String url = (callbackUrl != null && !callbackUrl.isBlank()) ? callbackUrl : engine.callbackUrl();
        callbacks.schedule(reference, amount, currency, scenario.callbacks(), url);

        SubmitBehaviour onSubmit = scenario.onSubmit();
        if (onSubmit.outcome() == SubmitOutcome.NO_RESPONSE) {
            return neverAnswer();
        }

        sleep(onSubmit.delay());
        return switch (onSubmit.outcome()) {
            case ACCEPT -> ResponseEntity.accepted().build();
            case CONFLICT, BAD_REQUEST, SERVER_ERROR -> MtnErrorResponse.forSubmit(onSubmit);
            case NO_RESPONSE -> throw new IllegalStateException("handled above");
        };
    }

    @GetMapping("/disbursement/v1_0/transfer/{referenceId}")
    public Map<String, String> status(
            @PathVariable String referenceId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        authenticator.require(authorization);

        QueryBehaviour behaviour = engine.nextQueryBehaviour(References.canonical(referenceId))
                .orElseThrow(() -> new MtnErrorException(HttpStatus.NOT_FOUND, MtnErrorResponse.notFound()));

        sleep(behaviour.delay());

        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", behaviour.status().name());
        if (behaviour.reason() != null && !behaviour.reason().isBlank()) {
            response.put("reason", behaviour.reason());
        }
        return response;
    }

    private static DeferredResult<ResponseEntity<Void>> neverAnswer() {
        return new DeferredResult<>(Duration.ofHours(1).toMillis());
    }

    private static String requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw MtnErrorResponse.invalidReference("X-Reference-Id header is required.");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw MtnErrorResponse.invalidReference("X-Reference-Id must be a UUID.");
        }
    }

    private static String payeeId(Map<String, Object> body) {
        if (body != null && body.get("payee") instanceof Map<?, ?> payee && payee.get("partyId") != null) {
            return String.valueOf(payee.get("partyId"));
        }
        return null;
    }

    private static String field(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static void sleep(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted while applying scenario delay");
        }
    }
}
