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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@RestController
@RequestMapping("/callbacks")
class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    private final AdapterRegistry adapters;
    private final PaymentRepository payments;
    private final SettlementService settlement;

    CallbackController(AdapterRegistry adapters, PaymentRepository payments, SettlementService settlement) {
        this.adapters = adapters;
        this.payments = payments;
        this.settlement = settlement;
    }

    @PostMapping("/{providerId}")
    ResponseEntity<Void> receive(@PathVariable String providerId,
                                 @RequestHeader Map<String, String> headers,
                                 @RequestBody(required = false) String body) {

        ProviderId id = providerId(providerId);
        ProviderAdapter adapter = adapters.find(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ProblemTypes.UNKNOWN_CALLBACK_PROVIDER, "No such provider",
                "This server has no adapter for provider '" + providerId + "'."));

        CallbackEvent event;
        try {
            event = adapter.parseCallback(new RawCallback(headers, body == null ? "" : body));
        } catch (UntrustedCallbackException notParseable) {
            // For MTN this only ever means "not a well-formed MTN callback" — there is no
            // signature to fail. 400 says exactly that and nothing about our data.
            log.info("rejected an unparseable callback on /callbacks/{}: {}", providerId, notParseable.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.UNPARSEABLE_CALLBACK,
                    "The callback could not be parsed",
                    "The body is not a well-formed " + providerId + " callback.");
        }

        ReferenceId reference = event.reference();
        if (payments.findByReference(reference).isEmpty()) {
            // 202, not 404: this endpoint is public, and a 404-vs-202 difference here is an
            // oracle for enumerating references. Write nothing; log it — misconfiguration
            // or a probe, both worth seeing.
            log.warn("callback on /callbacks/{} names reference {}, which this gateway never issued",
                    providerId, reference);
            return ResponseEntity.accepted().build();
        }

        settlement.confirm(id, reference, PaymentTransition.Cause.CALLBACK);
        return ResponseEntity.accepted().build();
    }

    private static ProviderId providerId(String raw) {
        try {
            return ProviderId.of(raw);
        } catch (RuntimeException notAProviderId) {
            throw new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.UNKNOWN_CALLBACK_PROVIDER, "No such provider",
                    "'" + raw + "' is not a provider this server knows.");
        }
    }
}
