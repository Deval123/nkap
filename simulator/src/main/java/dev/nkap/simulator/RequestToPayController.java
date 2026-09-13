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
 * The MTN MoMo Collections {@code requesttopay} surface. The controller decides
 * nothing about payment behaviour: it asks {@link ScenarioEngine} what to do and
 * carries out the answer (ADR 0002). What stays here are the protocol rules that
 * are not simulated behaviour:
 *
 * <ul>
 *   <li>{@code X-Reference-Id} is a client-supplied UUID and the idempotency
 *       key. A second POST with the same one is a 409.</li>
 *   <li>A missing or malformed {@code X-Reference-Id} is a 400.</li>
 *   <li>The POST returns 202 with an <em>empty</em> body: the status is only
 *       ever available through the GET.</li>
 *   <li>A GET on an unknown reference is a 404.</li>
 *   <li>Every one of those errors carries an MTN-shaped body — see
 *       {@link MtnErrorResponse}, issue #26.</li>
 * </ul>
 */
@RestController
public class RequestToPayController {

    private final CollectionRequestStore store;
    private final ScenarioEngine engine;
    private final CallbackDispatcher callbacks;
    private final TokenAuthenticator authenticator;

    RequestToPayController(CollectionRequestStore store, ScenarioEngine engine,
                           CallbackDispatcher callbacks, TokenAuthenticator authenticator) {
        this.store = store;
        this.engine = engine;
        this.callbacks = callbacks;
        this.authenticator = authenticator;
    }

    /**
     * The return type is {@code Object} because this handler answers two ways:
     * a plain {@link ResponseEntity} for every terminal outcome, or a
     * {@link DeferredResult} for {@code NO_RESPONSE}. Spring dispatches on the
     * runtime type, so both work without any manual async plumbing.
     */
    @PostMapping("/collection/v1_0/requesttopay")
    public Object requestToPay(
            @RequestHeader(value = "X-Reference-Id", required = false) String referenceId,
            @RequestHeader(value = "X-Callback-Url", required = false) String callbackUrl,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {

        // Order of rejection, decided on purpose: the request must be
        // well-formed (a malformed X-Reference-Id is a 400), then authenticated
        // (401 when the scenario enforces token expiry), then idempotent (a
        // reused reference is a 409) — all before the scenario is consulted.
        String reference = requireUuid(referenceId);
        authenticator.require(authorization);

        String msisdn = payerId(body);
        String amount = field(body, "amount");
        String currency = field(body, "currency");

        if (!store.record(reference)) {
            throw new MtnErrorException(HttpStatus.CONFLICT, MtnErrorResponse.duplicateReference());
        }

        Scenario scenario = engine.resolveForSubmission(reference, msisdn, amount, currency);

        // Schedule callbacks now — when the scenario resolves, and BEFORE the
        // submit delay below. Schedule first, sleep second, respond third: that
        // ordering is the only reason a callback declared with after:PT0S can
        // reach the client while this call is still blocked on onSubmit.delay()
        // — issue #6, the callback that arrives before the submit response. It
        // looks arbitrary until you need it.
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

    @GetMapping("/collection/v1_0/requesttopay/{referenceId}")
    public Map<String, String> status(
            @PathVariable String referenceId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        // Authenticate before revealing whether the reference exists: a 401
        // takes priority over the 404. There is no request-shape check here to
        // come first, unlike the submit path.
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

    /**
     * The {@code NO_RESPONSE} outcome: the operator accepted the request and
     * then went silent. A {@link DeferredResult} that is never completed, timing
     * out only after an hour — longer than any real client waits.
     *
     * <p>This is deliberately <strong>not</strong> a {@code Thread.sleep} on the
     * request thread: one blocked servlet thread per call would drain the pool
     * as soon as a handful of timeout tests run in parallel, and running tests
     * in parallel is exactly what a simulator exists to allow. Do not "simplify"
     * it into a sleep.
     */
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

    private static String payerId(Map<String, Object> body) {
        if (body != null && body.get("payer") instanceof Map<?, ?> payer && payer.get("partyId") != null) {
            return String.valueOf(payer.get("partyId"));
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
