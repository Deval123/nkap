package dev.nkap.simulator;

import com.fasterxml.jackson.databind.JsonMappingException;
import dev.nkap.simulator.scenario.ScenarioEngine;
import dev.nkap.simulator.scenario.ScenarioRule;
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

    ControlPlaneController(ScenarioEngine engine, CollectionRequestStore store) {
        this.engine = engine;
        this.store = store;
    }

    /** The request and response body of {@code /_nkap/scenarios}. */
    public record RuleList(List<ScenarioRule> rules) {
        public RuleList {
            rules = rules != null ? List.copyOf(rules) : List.of();
        }
    }

    /** The body of {@code GET /_nkap/state/{referenceId}}. */
    public record StateView(String scenario, int queryCount, Instant submittedAt) {}

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceRules(@RequestBody RuleList body) {
        engine.replaceRules(body.rules());
    }

    @GetMapping("/scenarios")
    public RuleList currentRules() {
        return new RuleList(engine.rules());
    }

    @DeleteMapping("/scenarios")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetRules() {
        engine.resetRules();
    }

    @GetMapping("/state/{referenceId}")
    public StateView state(@PathVariable String referenceId) {
        return engine.state(References.canonical(referenceId))
            .map(s -> new StateView(s.scenario().name(), s.queryCount(), s.submittedAt()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown reference"));
    }

    /**
     * Forgets every reference, in the engine and in the idempotency gate. This
     * is what lets one test suite run its cases in sequence without each
     * inheriting the previous one's references.
     */
    @DeleteMapping("/state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgetState() {
        engine.forgetAllState();
        store.clear();
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
