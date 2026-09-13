package dev.nkap.server.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.idempotency.IdempotencyKey;
import dev.nkap.core.idempotency.IdempotencyStore;
import dev.nkap.core.idempotency.IdempotentOutcome;
import dev.nkap.core.idempotency.RequestFingerprint;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.RefundService;
import java.math.BigInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /payments/{reference}/refunds} — issue #84, ADR 0010. A refund is a payment:
 * it is created here, submitted exactly like {@code POST /payments}, and from then on
 * polled at {@code GET /payments/{reference}} and notified by webhook like anything else.
 * There is no {@code GET /payments/{reference}/refunds} — a refund's own reference is
 * everything a caller needs, once it is returned.
 *
 * <p>Every rule that does not depend on concurrency is checked here, before any idempotency
 * claim or payment exists — the same discipline {@code PaymentController} applies to a
 * currency or a country: the original must be a {@code SUCCEEDED} collection belonging to
 * this merchant, the request must not name its own destination, and the amount (defaulting
 * to the full remaining balance) must not exceed what this read says is left. The one rule
 * that <strong>does</strong> depend on concurrency — the cap under two refunds issued at
 * once — is re-checked under lock by {@link RefundService}, because this read is stale the
 * moment a concurrent request passes it too.
 */
@RestController
@RequestMapping("/payments/{reference}/refunds")
class RefundController {

    private final RefundService refunds;
    private final PaymentRepository repository;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;

    RefundController(RefundService refunds, PaymentRepository repository, IdempotencyStore idempotency, ObjectMapper json) {
        this.refunds = refunds;
        this.repository = repository;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping
    ResponseEntity<String> create(
            ApiCredential caller,
            @PathVariable String reference,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RefundRequest requestBody) {

        RefundRequest request = requestBody == null ? new RefundRequest(null, null) : requestBody;

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.MISSING_IDEMPOTENCY_KEY,
                    "Idempotency-Key is required",
                    "POST /payments/{reference}/refunds requires an Idempotency-Key header so a retry cannot refund twice.");
        }

        // Rule 2 (issue #84): a supplied destination is refused outright, not ignored --
        // silence here would teach a caller the field works. Checked before the original is
        // even looked up, the same way a malformed body is rejected before anything else.
        if (!request.counterpartyMsisdn().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.REFUND_DESTINATION_NOT_ALLOWED,
                    "A refund cannot name its own destination",
                    "A refund always goes back to the payer of the original collection. Remove "
                            + "counterpartyMsisdn from the request; it is never read.");
        }

        ReferenceId originalReference = parseReference(reference);
        Payment original = repository.findByReference(originalReference)
                .filter(p -> p.merchantId().equals(caller.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.PAYMENT_NOT_FOUND,
                        "No such payment", "No payment exists for reference " + reference + "."));

        if (original.intent().operation() != Capability.Operation.COLLECT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.CANNOT_REFUND_A_DISBURSEMENT,
                    "Only a collection can be refunded",
                    reference + " is a " + original.intent().operation() + ", not a collection. Sending money "
                            + "back to a payee Nkap already paid is a new collection, not a refund.");
        }
        if (original.state() != PaymentState.SUCCEEDED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.ORIGINAL_NOT_REFUNDABLE,
                    "Only a settled collection can be refunded",
                    reference + " is " + original.state() + ", not SUCCEEDED. " + (original.state() == PaymentState.UNKNOWN
                            ? "Whether the payer's money was ever taken is not yet known -- poll GET /payments/"
                                    + reference + " until it resolves."
                            : "No refund was created."));
        }

        Money remaining = original.refundableRemaining();
        Money amount = request.amount() == null ? remaining : toMoney(request.amount(), remaining);
        if (!amount.isPositive()) {
            throw invalid("amount must be a positive number of minor units, was " + amount.amount());
        }
        if (amount.compareTo(remaining) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.REFUND_EXCEEDS_REMAINING,
                    "This refund would exceed what remains of the original",
                    reference + " has " + remaining + " left to refund; this request was for " + amount + ".");
        }

        IdempotencyKey key = new IdempotencyKey(caller.merchantId(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.of(reference + ":" + canonical(request));

        return switch (idempotency.begin(key, fingerprint)) {
            case IdempotentOutcome.Proceed ignored -> proceed(key, original, amount);
            case IdempotentOutcome.Replay replay -> replay(replay);
            case IdempotentOutcome.Conflict ignored -> throw new ApiException(HttpStatus.CONFLICT,
                    ProblemTypes.IDEMPOTENCY_KEY_REUSE, "Idempotency-Key reused with a different body",
                    "This Idempotency-Key was already used for a different request. Use a new key, "
                            + "or resend the original body to replay the original answer.");
            case IdempotentOutcome.InProgress ignored -> throw new ApiException(HttpStatus.CONFLICT,
                    ProblemTypes.REQUEST_IN_PROGRESS, "A request with this Idempotency-Key is still in progress",
                    "An earlier request with this Idempotency-Key has not finished. Retry once it has.");
        };
    }

    private ResponseEntity<String> proceed(IdempotencyKey key, Payment original, Money amount) {
        Payment refund;
        try {
            refund = refunds.createAndSubmit(original, amount);
        } catch (RuntimeException failedBeforePersist) {
            // RefundService.createAndSubmit only throws before it persists anything -- the
            // same contract PaymentService.createAndSubmit documents. Release the claim so a
            // retry is not blocked by a request nothing durable came of.
            idempotency.abandon(key);
            throw failedBeforePersist;
        }

        Rendered rendered = render(refund);
        idempotency.complete(key, envelope(rendered));
        return ResponseEntity.status(rendered.status())
                .header(HttpHeaders.LOCATION, "/payments/" + refund.reference())
                .contentType(MediaType.APPLICATION_JSON)
                .body(rendered.body());
    }

    private ResponseEntity<String> replay(IdempotentOutcome.Replay replay) {
        StoredResponse stored = read(replay.storedResponse(), StoredResponse.class);
        return ResponseEntity.status(stored.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(stored.body());
    }

    private ReferenceId parseReference(String reference) {
        try {
            return ReferenceId.of(reference);
        } catch (RuntimeException notAReference) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REFERENCE,
                    "The reference is not well-formed", "'" + reference + "' is not a payment reference.");
        }
    }

    private Money toMoney(BigInteger amount, Money remaining) {
        long minorUnits;
        try {
            minorUnits = amount.longValueExact();
        } catch (ArithmeticException e) {
            throw invalid("amount " + amount + " does not fit a minor-unit count");
        }
        return Money.of(minorUnits, remaining.currency());
    }

    // --- rendering and the stored envelope ------------------------------------

    private record Rendered(int status, String body) {}

    /** The response envelope kept for replay: the status and the exact body. */
    private record StoredResponse(int status, String body) {}

    private Rendered render(Payment refund) {
        int status = refund.state().needsResolution() ? HttpStatus.ACCEPTED.value() : HttpStatus.CREATED.value();
        return new Rendered(status, write(PaymentResponse.of(refund)));
    }

    private String envelope(Rendered rendered) {
        return write(new StoredResponse(rendered.status(), rendered.body()));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise a response", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read a stored response", e);
        }
    }

    private String canonical(RefundRequest request) {
        return write(request);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST,
                "The request is not valid", detail);
    }
}
