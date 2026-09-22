package dev.nkap.server.web;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.UntrustedCallbackException;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.provider.AdapterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /callbacks/{providerId}} and {@code POST /callbacks/{providerId}/{reference}}
 * — the operator's webhook, one handler, two routes (issue #185).
 *
 * <p>The second route exists so a callback can be attributed by the address the gateway
 * chose to hand the operator, not only by whatever the callback's own body carries: the
 * gateway knows which payment a callback concerns before anything has parsed it, because
 * {@code PublicBaseUrl} composed that address itself. The first route is not going away —
 * MTN's {@code providerCallbackHost} is fixed at API-user creation, and any deployment
 * already receiving callbacks on the old shape is pointed at it — so both are served by this
 * one method, and the path's reference is used only when {@link CallbackEvent#reference()}
 * itself carries none.
 *
 * <p>The endpoint is <strong>unauthenticated</strong> (MTN sends no signature we have
 * observed), and safe anyway because it settles nothing by itself: a callback is a hint
 * that asking is now worthwhile, and {@link SettlementService} confirms with
 * {@code adapter.query} before anything is written.
 *
 * <p>The status codes carry meaning:
 *
 * <ul>
 *   <li><strong>202</strong> whenever the gateway took responsibility for the message —
 *       including a callback naming a reference it never issued. A 404 there would let a
 *       caller probe which references exist. On the second route the reference sits in the
 *       path itself, which makes 404-on-a-bad-reference the obvious thing to reach for —
 *       resist it: known, unknown or not even a well-formed reference at all, the path
 *       segment gets exactly the same {@code 202}, for exactly the same reason.</li>
 *   <li><strong>400</strong> only when the body cannot be parsed as this provider's
 *       callback. That says nothing about our data.</li>
 *   <li><strong>404</strong> when {@code providerId} names no configured adapter.</li>
 * </ul>
 *
 * <p>Nothing is written for an unparseable, unknown-reference or unresolved-provider-reference
 * callback — a conformance rule.
 *
 * <p><strong>Some operators' callbacks never carry any reference Nkap chose (issue #149, ADR
 * 0011 §2)</strong> — the adapter hands back {@link CallbackEvent#unattributed}, carrying only
 * the operator's own reference, and this class resolves it against the
 * {@code reference ↔ provider_reference} association {@code PaymentRepository} has held on
 * every payment since {@code V1__initial_schema.sql}, the same association {@code query}
 * already reads in the other direction. When nothing matches — a submission whose response
 * never arrived, so the association itself was never recorded, <em>or</em> more than one
 * payment matches, which {@link PaymentRepository#findByProviderReference} refuses to guess
 * between — the answer is still {@code 202} with nothing written, for the same anti-oracle
 * reason as an unknown reference. <strong>The value itself is never logged</strong>: unlike
 * Nkap's own reference, {@code providerReference} is whatever the adapter read out of the
 * request body, so it is exactly as attacker-controlled as the body itself, and reaching this
 * branch costs an attacker nothing — inventing one is free, no guessing of a real value
 * required, since a miss is what gets here. It gets the same bounded log treatment as an
 * unparseable or unknown-reference callback, under its own reason, so the two kinds of
 * "cannot act on this" traffic can still be told apart by whoever is alerting on the counter,
 * even though neither is safe to write one line per request for.
 *
 * <p><strong>What this class does about being unauthenticated and internet-reachable by
 * design (issue #129; {@code docs/security-notes.md} §5 records a public hostname scanned
 * within forty-five minutes of coming up):</strong> every request increments a counter —
 * O(1), and unbounded traffic cannot grow it — tagged only with a configured provider id or
 * the fixed literal {@code "unknown"}, never the raw path segment, which an attacker could
 * otherwise use to grow the metrics registry without bound. A line is written only for a
 * callback naming a reference this deployment actually issued, since that traffic is bounded
 * by real payments, not by an attacker's request rate. An unparseable callback, one naming a
 * reference this deployment never issued, or one naming only a provider reference that never
 * resolves to a payment is exactly what a scanner or an invented value produces fast and free —
 * none of the three needs a correct guess, only a miss, and a miss is what reaches every one of
 * them. All three are counted on every request but logged <strong>at most once, ever, per
 * process</strong> — not sampled, not rate-limited, so the log cannot be driven by volume at
 * all — and never with the exception's own message, the request body, or the provider reference
 * an adapter read out of it: each can echo attacker-controlled text, which is a log-injection
 * surface, not a debugging convenience.
 */
@RestController
@RequestMapping("/callbacks")
class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    private static final String UNKNOWN_PROVIDER_TAG = "unknown";

    private final AdapterRegistry adapters;
    private final PaymentRepository payments;
    private final SettlementService settlement;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean loggedUnparseableOnce = new AtomicBoolean(false);
    private final AtomicBoolean loggedUnknownReferenceOnce = new AtomicBoolean(false);
    private final AtomicBoolean loggedUnresolvedProviderReferenceOnce = new AtomicBoolean(false);

    CallbackController(AdapterRegistry adapters, PaymentRepository payments, SettlementService settlement,
                        MeterRegistry meterRegistry) {
        this.adapters = adapters;
        this.payments = payments;
        this.settlement = settlement;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/{providerId}")
    ResponseEntity<Void> receive(@PathVariable String providerId,
                                 @RequestHeader Map<String, String> headers,
                                 @RequestBody(required = false) String body) {
        return handle(providerId, null, headers, body);
    }

    /**
     * The reference is a raw, unvalidated path segment on purpose — never {@code ReferenceId}
     * typed here, so a malformed one reaches {@link #handle} rather than failing Spring's own
     * argument binding with some other status. It is used only when
     * {@link CallbackEvent#reference()} carries none; see the class javadoc.
     */
    @PostMapping("/{providerId}/{reference}")
    ResponseEntity<Void> receive(@PathVariable String providerId, @PathVariable String reference,
                                 @RequestHeader Map<String, String> headers,
                                 @RequestBody(required = false) String body) {
        return handle(providerId, reference, headers, body);
    }

    private ResponseEntity<Void> handle(String providerId, String pathReference,
                                        Map<String, String> headers, String body) {

        // Every request, before anything else can reject it — "is this happening at all"
        // must survive whatever the other branches below do. The tag is the *resolved*
        // ProviderId's own canonical value, never the raw path segment: if ProviderId.of
        // ever normalises (case, trimming), a raw-string tag would let every distinct
        // spelling of one configured provider grow the registry, exactly the unbounded
        // cardinality this class exists to avoid.
        Optional<ProviderId> configured = tryResolve(providerId);
        String providerTag = configured.map(ProviderId::toString).orElse(UNKNOWN_PROVIDER_TAG);
        countReceived(providerTag);

        ProviderId id = providerId(providerId);
        ProviderAdapter adapter = configured.flatMap(adapters::find).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ProblemTypes.UNKNOWN_CALLBACK_PROVIDER, "No such provider",
                "This server has no adapter for provider '" + providerId + "'."));

        CallbackEvent event;
        try {
            event = adapter.parseCallback(new RawCallback(headers, body == null ? "" : body));
        } catch (UntrustedCallbackException notParseable) {
            countRejected(providerTag, "unparseable");
            if (loggedUnparseableOnce.compareAndSet(false, true)) {
                // For MTN this only ever means "not a well-formed MTN callback" — there is
                // no signature to fail. Never notParseable.getMessage(): a parse failure can
                // echo a substring pulled from the request body (see class javadoc).
                log.warn("rejected an unparseable callback on /callbacks/{} "
                        + "(further occurrences are counted in nkap_callback_rejected, not logged)", providerId);
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.UNPARSEABLE_CALLBACK,
                    "The callback could not be parsed",
                    "The body is not a well-formed " + providerId + " callback.");
        }

        ReferenceId reference = event.reference();
        if (reference == null && pathReference != null) {
            // Attribution by the address the gateway chose, not by anything the body
            // carries (issue #185) -- the whole point of the second route. A path segment
            // that is not even a well-formed reference is not distinguished from a
            // well-formed one this gateway never issued: both fall through to the same
            // "unknown reference" branch below, which answers both identically, on purpose
            // (see the class javadoc).
            reference = tryParseReference(pathReference);
        } else if (reference == null) {
            // This operator's callback never carries a value Nkap chose (issue #149, ADR
            // 0011 §2) -- only the operator's own reference, which the adapter has already
            // handed back as event.providerReference() (never blank here: CallbackEvent's
            // own compact constructor refuses a callback with neither identity). Resolve it
            // against the association PaymentRepository already builds for query() (issue
            // #96), in the other direction.
            Optional<Payment> resolved = payments.findByProviderReference(id, event.providerReference());
            if (resolved.isEmpty()) {
                countRejected(providerTag, "unresolved_provider_reference");
                if (loggedUnresolvedProviderReferenceOnce.compareAndSet(false, true)) {
                    // The same bounded treatment as unparseable/unknown_reference, and for
                    // the same reason: this branch is reached by a miss, not a correct guess
                    // -- an attacker who invents any provider reference gets here for free,
                    // at their own request rate, exactly like an invented Nkap reference.
                    // Never event.providerReference() itself: it is whatever the adapter
                    // read out of the request body, on an unauthenticated endpoint, so it is
                    // exactly as attacker-controlled as the body itself (issue #129) -- and
                    // never logged, not even MDC-only, for the same reason the parse
                    // failure's own message above never is. The message says only what is
                    // true either way -- findByProviderReference returns empty for zero
                    // matches and for more than one alike (PaymentRepository's own contract),
                    // so this line cannot claim "never recorded" without claiming more than it
                    // knows. Distinguished from unknown_reference by its own counter reason
                    // rather than by log volume: this one may mean a real payment's submit
                    // response was lost (docs/providers/m-pesa.md), which is worth alerting on
                    // differently, not worth logging more often.
                    log.warn("callback on /callbacks/{} names a provider reference this gateway cannot resolve "
                            + "to exactly one payment "
                            + "(further occurrences are counted in nkap_callback_rejected, not logged)", providerId);
                }
                return ResponseEntity.accepted().build();
            }
            reference = resolved.get().reference();
        }
        if (reference == null || payments.findByReference(reference).isEmpty()) {
            countRejected(providerTag, "unknown_reference");
            if (loggedUnknownReferenceOnce.compareAndSet(false, true)) {
                // 202, not 404: this endpoint is public, and a 404-vs-202 difference here is
                // an oracle for enumerating references. A well-formed reference is safe to
                // log (it only reaches this line already shaped like this deployment's own
                // reference); a null one means the path segment on the second route was not
                // even well-formed, which is exactly as cheap for an attacker to produce, so
                // it gets the same bounded treatment and never appears in the message either
                // -- logging the raw path segment would be logging attacker-controlled text
                // (issue #129).
                if (reference != null) {
                    log.warn("callback on /callbacks/{} names reference {}, which this gateway never issued "
                            + "(further occurrences are counted in nkap_callback_rejected, not logged)", providerId, reference);
                } else {
                    log.warn("callback on /callbacks/{} names a path reference that is not well-formed "
                            + "(further occurrences are counted in nkap_callback_rejected, not logged)", providerId);
                }
            }
            return ResponseEntity.accepted().build();
        }

        countConfirmed(providerTag);
        // A reference this deployment issued is not guessable, so this line is bounded by
        // real traffic rather than by an attacker's request rate — safe to write every time,
        // unlike the two branches above. MDC here, not just in the message, is what makes
        // this line joinable to whatever SettlementService.confirm logs next on this same
        // thread about the same reference (issue #75), including the transition it causes.
        try (var ignored = MDC.putCloseable("reference", reference.toString())) {
            log.info("callback on /callbacks/{} received for reference {}", providerId, reference);
        }
        settlement.confirm(id, reference, PaymentTransition.Cause.CALLBACK);
        return ResponseEntity.accepted().build();
    }

    /** The configured {@link ProviderId} named by {@code raw}, or empty — never the adapter itself,
     * so the caller always has the canonical id to tag with, whether or not it also needs the
     * adapter. */
    private Optional<ProviderId> tryResolve(String raw) {
        try {
            ProviderId candidate = ProviderId.of(raw);
            return adapters.find(candidate).isPresent() ? Optional.of(candidate) : Optional.empty();
        } catch (RuntimeException notAProviderId) {
            return Optional.empty();
        }
    }

    private static ProviderId providerId(String raw) {
        try {
            return ProviderId.of(raw);
        } catch (RuntimeException notAProviderId) {
            throw new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.UNKNOWN_CALLBACK_PROVIDER, "No such provider",
                    "'" + raw + "' is not a provider this server knows.");
        }
    }

    /**
     * {@code null} for a path segment that is not a well-formed {@link ReferenceId} — never
     * thrown, so a malformed second-route reference reaches the same {@code 202} the rest of
     * this method already gives an unknown one (see the class javadoc's note on why a 404
     * here would be a mistake).
     */
    private static ReferenceId tryParseReference(String raw) {
        try {
            return ReferenceId.of(raw);
        } catch (RuntimeException notAReference) {
            return null;
        }
    }

    private void countReceived(String providerTag) {
        Counter.builder("nkap.callback.received")
                .description("Every request to the callback endpoint, parseable or not, known "
                        + "reference or not. Unauthenticated and internet-reachable by design "
                        + "(docs/security-notes.md #5) -- this counter, not a log line, is how a "
                        + "flood of it is answered.")
                .tag("provider", providerTag)
                .register(meterRegistry)
                .increment();
    }

    private void countRejected(String providerTag, String reason) {
        Counter.builder("nkap.callback.rejected")
                .description("Callbacks this gateway would not act on: unparseable, naming a "
                        + "reference it never issued, or (reason=unresolved_provider_reference, "
                        + "issue #149) naming only a provider reference it has never recorded "
                        + "against a payment. All three are reached by a miss, not a correct "
                        + "guess -- an attacker gets here as cheaply by inventing a value as by "
                        + "guessing a real one -- so the log carries at most one example of "
                        + "each, ever; this counter carries the full count for all three, and "
                        + "its own reason tag is what lets unresolved_provider_reference be "
                        + "watched more closely than an ordinary unknown reference, since it "
                        + "may mean a real payment's submit response was lost.")
                .tag("provider", providerTag)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private void countConfirmed(String providerTag) {
        Counter.builder("nkap.callback.confirmed")
                .description("Callbacks naming a reference this deployment issued -- bounded by "
                        + "real traffic, since that reference is not guessable, unlike the "
                        + "request rate itself.")
                .tag("provider", providerTag)
                .register(meterRegistry)
                .increment();
    }
}
