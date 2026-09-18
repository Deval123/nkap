package dev.nkap.server.web;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.UntrustedCallbackException;
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
 * {@code POST /callbacks/{providerId}} — the operator's webhook.
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
 *       caller probe which references exist.</li>
 *   <li><strong>400</strong> only when the body cannot be parsed as this provider's
 *       callback. That says nothing about our data.</li>
 *   <li><strong>404</strong> when {@code providerId} names no configured adapter.</li>
 * </ul>
 *
 * <p>Nothing is written for an unparseable or unknown-reference callback — a conformance
 * rule.
 *
 * <p><strong>What this class does about being unauthenticated and internet-reachable by
 * design (issue #129; {@code docs/security-notes.md} §5 records a public hostname scanned
 * within forty-five minutes of coming up):</strong> every request increments a counter —
 * O(1), and unbounded traffic cannot grow it — tagged only with a configured provider id or
 * the fixed literal {@code "unknown"}, never the raw path segment, which an attacker could
 * otherwise use to grow the metrics registry without bound. A line is written only for a
 * callback naming a reference this deployment actually issued, since that traffic is bounded
 * by real payments, not by an attacker's request rate. An unparseable callback, or one naming
 * a reference this deployment never issued, is exactly what a scanner or a guess produces
 * fast and free; both are counted on every request but logged <strong>at most once, ever, per
 * process</strong> — not sampled, not rate-limited, so the log cannot be driven by volume at
 * all — and never with the exception's own message or the request body: either can echo
 * attacker-controlled text pulled from the body, which is a log-injection surface, not a
 * debugging convenience.
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

        // Every request, before anything else can reject it — "is this happening at all"
        // must survive whatever the other branches below do.
        Optional<ProviderAdapter> resolved = tryResolve(providerId);
        String providerTag = resolved.isPresent() ? providerId : UNKNOWN_PROVIDER_TAG;
        countReceived(providerTag);

        ProviderId id = providerId(providerId);
        ProviderAdapter adapter = resolved.orElseThrow(() -> new ApiException(
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
                        + "(further occurrences are counted, not logged)", providerId);
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.UNPARSEABLE_CALLBACK,
                    "The callback could not be parsed",
                    "The body is not a well-formed " + providerId + " callback.");
        }

        ReferenceId reference = event.reference();
        if (payments.findByReference(reference).isEmpty()) {
            countRejected(providerTag, "unknown_reference");
            if (loggedUnknownReferenceOnce.compareAndSet(false, true)) {
                // 202, not 404: this endpoint is public, and a 404-vs-202 difference here is
                // an oracle for enumerating references. The reference itself is safe to log
                // (it only reaches this line already shaped like this deployment's own
                // reference), but a real reference this deployment never issued is exactly
                // as cheap for an attacker to produce as an unparseable body, so it gets the
                // same bounded treatment, not an unconditional line.
                log.warn("callback on /callbacks/{} names reference {}, which this gateway never issued "
                        + "(further occurrences are counted, not logged)", providerId, reference);
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

    private Optional<ProviderAdapter> tryResolve(String raw) {
        try {
            return adapters.find(ProviderId.of(raw));
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
                .description("Callbacks this gateway would not act on: unparseable, or naming a "
                        + "reference it never issued. Both are as cheap for an attacker to "
                        + "produce as the request rate itself, so this counter carries the "
                        + "count and the log carries at most one example, ever.")
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
