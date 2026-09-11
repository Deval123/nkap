package dev.nkap.simulator;

import com.fasterxml.jackson.databind.JsonMappingException;
import dev.nkap.simulator.scenario.AccountBehaviour;
import dev.nkap.simulator.scenario.ScenarioEngine;
import dev.nkap.simulator.scenario.ScenarioRule;
import dev.nkap.simulator.scenario.TokenBehaviour;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The scenario control plane. Namespaced under {@code /_nkap/} so it can never
 * collide with an operator path, and it is the one part of the simulator that
 * deliberately does not imitate MTN — it is how a test, in any language, tells
 * the simulator how to misbehave (ADR 0002).
 *
 * <p>Durations travel as ISO-8601 strings: {@code "PT2S"}, {@code "PT0S"}.
 */
@RestController
@RequestMapping("/_nkap")
public class ControlPlaneController {

    private final ScenarioEngine engine;
    private final CollectionRequestStore store;
    private final CallbackDispatcher callbacks;

    ControlPlaneController(ScenarioEngine engine, CollectionRequestStore store, CallbackDispatcher callbacks) {
        this.engine = engine;
        this.store = store;
        this.callbacks = callbacks;
    }

    /**
     * The request and response body of {@code /_nkap/scenarios}: the whole
     * declared configuration in one document — the token lifetime, the fallback
     * callback URL, the rule list, and the account balance / holder-validation
     * answers (issue #72). All optional; {@code token} defaults to one hour,
     * {@code rules} to empty, {@code callbackUrl} to none, {@code account} to the
     * default balance and an active holder. Posting it replaces the lot atomically.
     */
    public record Declaration(TokenBehaviour token, String callbackUrl, List<ScenarioRule> rules, AccountBehaviour account) {
        public Declaration {
            token = token != null ? token : new TokenBehaviour(null);
            rules = rules != null ? List.copyOf(rules) : List.of();
            account = account != null ? account : AccountBehaviour.defaultBehaviour();
        }
    }

    /** The body of {@code GET /_nkap/state/{referenceId}}. */
    public record StateView(String scenario, int queryCount, Instant submittedAt) {}

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void declare(@RequestBody Declaration body) {
        engine.replaceConfiguration(body.token(), body.callbackUrl(), body.rules(), body.account());
    }

    @GetMapping("/scenarios")
    public Declaration current() {
        return new Declaration(engine.token(), engine.callbackUrl(), engine.rules(), engine.account());
    }

    @DeleteMapping("/scenarios")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetDeclaration() {
        engine.resetConfiguration();
    }

    @GetMapping("/state/{referenceId}")
    public StateView state(@PathVariable String referenceId) {
        return engine.state(References.canonical(referenceId))
            .map(s -> new StateView(s.scenario().name(), s.queryCount(), s.submittedAt()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown reference"));
    }

    /**
     * The callback delivery attempts for a submission reference, oldest first.
     * An empty list when none were attempted — polling for "have the callbacks
     * gone out yet?" should not have to distinguish "not yet" from "never".
     * This is what lets a test assert two callbacks were sent, at the right
     * interval, without standing up a server to receive them.
     */
    @GetMapping("/callbacks/{referenceId}")
    public List<CallbackDispatcher.Attempt> callbackAttempts(@PathVariable String referenceId) {
        return callbacks.attemptsFor(References.canonical(referenceId));
    }

    /**
     * Forgets every reference — in the engine, the idempotency gate and the
     * callback log. This is what lets one test suite run its cases in sequence
     * without each inheriting the previous one's references. It leaves the
     * declared configuration — rules, token and callback URL — alone: that is
     * {@code DELETE /_nkap/scenarios}.
     */
    @DeleteMapping("/state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgetState() {
        engine.forgetAllState();
        store.clear();
        callbacks.clear();
    }

    /**
     * A malformed scenario is a 400 whose body names the offending field. A
     * contributor writing their first scenario will get it wrong, and this
     * message is what teaches them.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformedScenario(HttpMessageNotReadableException e) {
        String field = "(unknown)";
        if (e.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            field = mapping.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
        }
        return ResponseEntity.badRequest().body(Map.of(
            "error", "malformed scenario",
            "field", field,
            "detail", e.getMostSpecificCause().getMessage()));
    }
}
