package dev.nkap.simulator;

import dev.nkap.simulator.scenario.QueryBehaviour;
import dev.nkap.simulator.scenario.Scenario;
import dev.nkap.simulator.scenario.ScenarioEngine;
import dev.nkap.simulator.scenario.SubmitBehaviour;
import dev.nkap.simulator.scenario.SubmitOutcome;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.context.request.async.WebAsyncUtils;
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
 * </ul>
 */
@RestController
public class RequestToPayController {

    private final CollectionRequestStore store;
    private final ScenarioEngine engine;

    RequestToPayController(CollectionRequestStore store, ScenarioEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    @PostMapping("/collection/v1_0/requesttopay")
    public ResponseEntity<Void> requestToPay(
            @RequestHeader(value = "X-Reference-Id", required = false) String referenceId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        String reference = requireUuid(referenceId);
        String msisdn = payerId(body);
        String amount = field(body, "amount");
        String currency = field(body, "currency");

        // Protocol errors keep priority over any simulated behaviour: a reused
        // reference is a 409 before the scenario is even consulted.
        if (!store.record(reference)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "X-Reference-Id already used");
        }

        Scenario scenario = engine.resolveForSubmission(reference, msisdn, amount, currency);
        SubmitBehaviour onSubmit = scenario.onSubmit();

        if (onSubmit.outcome() == SubmitOutcome.NO_RESPONSE) {
            neverAnswer(request);
            return null;
        }

        sleep(onSubmit.delay());
        return switch (onSubmit.outcome()) {
            case ACCEPT -> ResponseEntity.accepted().build();
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case BAD_REQUEST -> ResponseEntity.badRequest().build();
            case SERVER_ERROR -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case NO_RESPONSE -> throw new IllegalStateException("handled above");
        };
    }

    @GetMapping("/collection/v1_0/requesttopay/{referenceId}")
    public Map<String, String> status(@PathVariable String referenceId) {
        QueryBehaviour behaviour = engine.nextQueryBehaviour(References.canonical(referenceId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown reference"));

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
     * then went silent. Implemented as a {@link DeferredResult} that is never
     * completed, timing out only after an hour — longer than any real client
     * waits. The handler then returns {@code null}: the response is entirely in
     * the hands of the (never-fired) deferred result.
     *
     * <p>This is deliberately <strong>not</strong> a {@code Thread.sleep} on the
     * request thread: one blocked servlet thread per call would drain the pool
     * as soon as a handful of timeout tests run in parallel, and running tests
     * in parallel is exactly what a simulator exists to allow. Do not "simplify"
     * it into a sleep.
     */
    private static void neverAnswer(HttpServletRequest request) {
        DeferredResult<ResponseEntity<Void>> deferred = new DeferredResult<>(Duration.ofHours(1).toMillis());
        try {
            WebAsyncUtils.getAsyncManager(request).startDeferredResultProcessing(deferred);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "could not start async processing", e);
        }
    }

    private static String requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "X-Reference-Id header is required");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "X-Reference-Id must be a UUID");
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
