package dev.nkap.simulator;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.simulator.scenario.AccountBehaviour;
import dev.nkap.simulator.scenario.Rule;
import dev.nkap.simulator.scenario.SubmitOutcome;
import dev.nkap.simulator.scenario.Timeline;
import dev.nkap.simulator.scenario.TimelineEngine;
import dev.nkap.simulator.scenario.TokenBehaviour;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * The scenario control plane. Namespaced under {@code /_nkap/} so it can never
 * collide with an operator path, and it is the one part of the simulator that
 * deliberately does not imitate any operator — it is how a test, in any language,
 * tells the simulator how to misbehave (ADR 0002).
 *
 * <p>Durations travel as ISO-8601 strings: {@code "PT2S"}, {@code "PT0S"}.
 *
 * <p>Everything here is the same for every operator except one thing: the words a
 * scenario is written in. So a face binds this class to its own declaration document,
 * {@code D}, by extending it — which is also what lets Spring bind a posted document to
 * the face's concrete types, exactly as it would bind a class of its own — and marks the
 * subclass as the controller.
 *
 * @param <D> the face's declaration document
 * @param <R> the face's rule
 */
@RequestMapping("/_nkap")
public abstract class ControlPlane<D extends ControlPlane.Declared<R>, R extends Rule<? extends Timeline<?, ?>>> {

    /**
     * What every face's declaration document holds: the whole declared configuration —
     * the token lifetime, the fallback callback URL, the rule list, and the account
     * balance / holder-validation answers (issue #72). Posting one replaces the lot
     * atomically.
     *
     * @param <R> the face's rule
     */
    public interface Declared<R> {

        TokenBehaviour token();

        String callbackUrl();

        List<R> rules();

        AccountBehaviour account();
    }

    private final TimelineEngine<R, ?, ?> engine;
    private final ReferenceStore store;
    private final CallbackDispatcher<?> callbacks;
    private final SubmissionCount submissions;
    private final PaymentIdentity identity;
    private final Class<D> documentType;

    protected ControlPlane(TimelineEngine<R, ?, ?> engine, ReferenceStore store, CallbackDispatcher<?> callbacks,
                           SubmissionCount submissions, PaymentIdentity identity, Class<D> documentType) {
        this.engine = engine;
        this.store = store;
        this.callbacks = callbacks;
        this.submissions = submissions;
        this.identity = identity;
        this.documentType = documentType;
    }

    /** The face's document holding exactly this configuration, for {@code GET /_nkap/scenarios}. */
    protected abstract D document(TokenBehaviour token, String callbackUrl, List<R> rules, AccountBehaviour account);

    /** The body of {@code GET /_nkap/state/{referenceId}}. */
    public record StateView(String scenario, int queryCount, Instant submittedAt) {}

    /** The body of {@code GET /_nkap/submissions}. */
    public record SubmissionsView(int count) {}

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void declare(@RequestBody D body) {
        refuseWhatThisOperatorCannotDo(body.rules());
        engine.replaceConfiguration(body.token(), body.callbackUrl(), body.rules(), body.account());
    }

    /**
     * A scenario the face's own declared policy says its operator can never play is refused
     * here, when it is declared, the way a malformed one is — not played by inventing an
     * answer on the wire for something the real operator was never seen to say. One such
     * case today: {@link SubmitOutcome#CONFLICT}, a refusal of a repeated reference, declared
     * against a face whose operator does not deduplicate ({@link PaymentIdentity.Repeat#NOT_DEDUPLICATED}).
     * Nothing is replaced when a declaration is refused.
     */
    private void refuseWhatThisOperatorCannotDo(List<R> rules) {
        if (identity.onRepeat() != PaymentIdentity.Repeat.NOT_DEDUPLICATED) {
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).scenario().onSubmit().outcome() == SubmitOutcome.CONFLICT) {
                throw new UnplayableScenario("rules.[" + i + "].scenario.onSubmit.outcome",
                        "This operator does not refuse a repeated reference (its face declares Repeat."
                                + PaymentIdentity.Repeat.NOT_DEDUPLICATED + "); this scenario declared CONFLICT anyway");
            }
        }
    }

    /**
     * A well-formed scenario naming something the face's declared policy rules out. Public
     * because the deployable's startup file loader reports it the way it reports a malformed
     * file, naming the file and {@link #offendingField(Throwable) the field}.
     */
    public static final class UnplayableScenario extends RuntimeException {

        private final String field;

        UnplayableScenario(String field, String detail) {
            super(detail);
            this.field = field;
        }

        /** The offending field, in the same notation {@link #offendingField(Throwable)} uses. */
        public String field() {
            return field;
        }
    }

    @GetMapping("/scenarios")
    public D current() {
        return document(engine.token(), engine.callbackUrl(), engine.rules(), engine.account());
    }

    @DeleteMapping("/scenarios")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetDeclaration() {
        engine.resetConfiguration();
    }

    /**
     * Issue #69: once a payment's identity can exist under more than one {@link Product}, this route
     * cannot answer from the reference alone without risking an answer for the wrong one.
     * Answers only when <strong>exactly one</strong> product holds {@code referenceId} —
     * {@code 404} both when neither does (unchanged: unknown reference, as before) and when
     * both do (new: a test that deliberately reused a reference across products gets a
     * refusal, not a silent guess at which one it meant) — the same reasoning
     * {@code PaymentRepository.findByProviderReference} applies on the gateway side.
     */
    @GetMapping("/state/{referenceId}")
    public StateView state(@PathVariable String referenceId) {
        String reference = identity.canonical(referenceId);
        Product product = onlyProductHolding(reference)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown reference"));
        return engine.state(product, reference)
            .map(s -> new StateView(s.scenario().name(), s.queryCount(), s.submittedAt()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown reference"));
    }

    /**
     * The callback delivery attempts for a submission reference, oldest first.
     * An empty list when none were attempted — polling for "have the callbacks
     * gone out yet?" should not have to distinguish "not yet" from "never", and that
     * reasoning is unaffected by issue #69: a reference neither product has ever seen is
     * still just that, not an error. Delivery itself is not partitioned per product (out of
     * scope for #69 — the callback mechanism), so the one new refusal this route needs is
     * the one case where that matters: {@code referenceId} used by <strong>both</strong>
     * products, where the log under it would otherwise silently mix two products' deliveries
     * under one answer. {@code 404} there, not a guess at whose callbacks these are.
     */
    @GetMapping("/callbacks/{referenceId}")
    public List<? extends CallbackDispatcher.Attempt<?>> callbackAttempts(@PathVariable String referenceId) {
        String reference = identity.canonical(referenceId);
        if (engine.productsHolding(reference).size() > 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "reference is used by more than one product; ask each product's own state instead");
        }
        return callbacks.attemptsFor(reference);
    }

    /**
     * Forgets every reference — in the engine, the idempotency gate and the
     * callback log — and resets the count of processed submissions. This is what lets one test suite run its cases in sequence
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
        submissions.reset();
        forgetFaceState();
    }

    /**
     * Forgets whatever state a face keeps of its own, beside what the core keeps, as part of
     * {@code DELETE /_nkap/state}. Nothing, unless a face overrides it. A face that records what
     * it received clears the record here, so a test never reads a previous test's requests.
     */
    protected void forgetFaceState() {
    }

    /**
     * How many submissions the operator has processed since {@code DELETE /_nkap/state} —
     * see {@link SubmissionCount} for exactly what counts. A test reads it to tell whether a
     * call reached the operator, and how many times (issue #175).
     */
    @GetMapping("/submissions")
    public SubmissionsView submissions() {
        return new SubmissionsView(submissions.processed());
    }

    /**
     * A malformed scenario is a 400 whose body names the offending field. A
     * contributor writing their first scenario will get it wrong, and this
     * message is what teaches them.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformedScenario(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "malformed scenario",
            "field", offendingField(e.getCause()),
            "detail", e.getMostSpecificCause().getMessage()));
    }

    /**
     * A scenario this face's operator cannot play is refused with the same 400, in the same
     * words, as a malformed one: to the test author both are a scenario that has to change.
     */
    @ExceptionHandler(UnplayableScenario.class)
    public ResponseEntity<Map<String, String>> unplayableScenario(UnplayableScenario e) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "malformed scenario",
            "field", e.field,
            "detail", e.getMessage()));
    }

    /**
     * Reads a declaration document from {@code path} with the same binding a {@code POST}
     * gets, and applies it exactly as {@link #declare} does — for the deployable's startup
     * scenario file. Returns how many rules it declared.
     */
    public int load(ObjectMapper json, Path path) throws IOException {
        D declaration = json.readValue(path.toFile(), documentType);
        declare(declaration);
        return declaration.rules().size();
    }

    /**
     * The offending field of a malformed declaration, walked from Jackson's own
     * path — {@code "(unknown)"} when the failure is not attributable to one field (a
     * syntax error, for instance) — or the field an {@link UnplayableScenario} names.
     * Shared with the deployable's startup file loader, which binds the same document from
     * a file at startup: the two ways of declaring a scenario diagnose a mistake in exactly
     * the same words, not two messages that happen to agree today.
     */
    public static String offendingField(Throwable cause) {
        if (cause instanceof UnplayableScenario unplayable) {
            return unplayable.field();
        }
        if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return mapping.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
        }
        return "(unknown)";
    }

    /** {@code reference}'s one product, or empty when neither or both hold it (issue #69). */
    private Optional<Product> onlyProductHolding(String reference) {
        Set<Product> holders = engine.productsHolding(reference);
        return holders.size() == 1 ? holders.stream().findFirst() : Optional.empty();
    }
}
