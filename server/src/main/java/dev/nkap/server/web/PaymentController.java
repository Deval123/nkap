package dev.nkap.server.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.idempotency.IdempotencyKey;
import dev.nkap.core.idempotency.IdempotencyStore;
import dev.nkap.core.idempotency.IdempotentOutcome;
import dev.nkap.core.idempotency.RequestFingerprint;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentService;
import dev.nkap.server.provider.AdapterRegistry;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payments surface. Two endpoints:
 *
 * <ul>
 *   <li>{@code POST /payments} — create one, hand it to the operator, answer honestly.
 *       An {@code Idempotency-Key} is required; all four store outcomes are handled; the
 *       reference is persisted before the operator is called.</li>
 *   <li>{@code GET /payments/{reference}} — read stored state. It never calls the
 *       operator: a read that hits a third party is a read that times out, and closing an
 *       {@code UNKNOWN} is the reconciler's job, not a refresh button's.</li>
 * </ul>
 */
@RestController
@RequestMapping("/payments")
class PaymentController {

    private final PaymentService payments;
    private final PaymentRepository repository;
    private final IdempotencyStore idempotency;
    private final AdapterRegistry adapters;
    private final ObjectMapper json;
    private final ProviderId defaultProvider;

    PaymentController(PaymentService payments, PaymentRepository repository, IdempotencyStore idempotency,
                      AdapterRegistry adapters, ObjectMapper json,
                      @Value("${nkap.provider.default}") String defaultProvider) {
        this.payments = payments;
        this.repository = repository;
        this.idempotency = idempotency;
        this.adapters = adapters;
        this.json = json;
        this.defaultProvider = ProviderId.of(defaultProvider);
    }

    @PostMapping
    ResponseEntity<String> create(
            ApiCredential caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreatePaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.MISSING_IDEMPOTENCY_KEY,
                    "Idempotency-Key is required",
                    "POST /payments requires an Idempotency-Key header so a retry cannot pay twice.");
        }

        // Validate before touching the idempotency store: a request that cannot be served
        // must not leave a claim behind that a retry would then collide with.
        PaymentIntent intent = toIntent(request);
        rejectUnservedCurrency(intent);
        // The merchant is the one the API key identifies, never a body field. This is what
        // makes the (merchant, key) scope of the idempotency store an identity the gateway
        // established rather than one the caller asserted — the hole this slice closes.
        IdempotencyKey key = new IdempotencyKey(caller.merchantId(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.of(canonical(request));

        return switch (idempotency.begin(key, fingerprint)) {
            case IdempotentOutcome.Proceed ignored -> proceed(key, intent);
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

    private ResponseEntity<String> proceed(IdempotencyKey key, PaymentIntent intent) {
        Payment payment;
        try {
            payment = payments.createAndSubmit(defaultProvider, key.merchantId(), intent);
        } catch (RuntimeException failedBeforePersist) {
            // createAndSubmit only throws before it has persisted anything — its contract.
            // Once the payment is saved a submit-time problem is recorded on it, not
            // thrown. So nothing durable was written here: release the claim for a retry.
            idempotency.abandon(key);
            throw failedBeforePersist;
        }

        Rendered rendered = render(payment);
        idempotency.complete(key, envelope(rendered));
        return ResponseEntity.status(rendered.status())
                .header(HttpHeaders.LOCATION, "/payments/" + payment.reference())
                .contentType(MediaType.APPLICATION_JSON)
                .body(rendered.body());
    }

    private ResponseEntity<String> replay(IdempotentOutcome.Replay replay) {
        StoredResponse stored = read(replay.storedResponse(), StoredResponse.class);
        return ResponseEntity.status(stored.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(stored.body());
    }

    @GetMapping("/{reference}")
    ResponseEntity<PaymentResponse> read(ApiCredential caller, @PathVariable String reference) {
        ReferenceId id;
        try {
            id = ReferenceId.of(reference);
        } catch (RuntimeException notAReference) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REFERENCE,
                    "The reference is not well-formed", "'" + reference + "' is not a payment reference.");
        }
        // A payment that belongs to another merchant answers exactly as one that does not
        // exist. Two different answers is an oracle for whether a reference exists — the
        // same reasoning that made an unknown callback reference a 202, not a 404.
        Payment payment = repository.findByReference(id)
                .filter(p -> p.merchantId().equals(caller.merchantId()))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, ProblemTypes.PAYMENT_NOT_FOUND, "No such payment",
                        "No payment exists for reference " + reference + "."));
        return ResponseEntity.ok(PaymentResponse.of(payment));
    }

    /**
     * Turns away a currency the addressed deployment does not settle, before any payment or
     * idempotency claim exists. This is not a failed payment: no operator was asked, and
     * the caller simply routed to an installation that does not serve that currency.
     */
    private void rejectUnservedCurrency(PaymentIntent intent) {
        Currency requested = intent.amount().currency();
        adapters.settlementCurrency(defaultProvider)
                .filter(settled -> settled != requested)
                .ifPresent(settled -> {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.UNSERVED_CURRENCY,
                            "This deployment does not serve that currency",
                            "Payments here settle in " + settled + "; this request was for " + requested
                                    + ". No payment was created.");
                });
    }

    // --- request -> intent -----------------------------------------------------

    private PaymentIntent toIntent(CreatePaymentRequest request) {
        requireText(request.operation(), "operation");
        requireText(request.currency(), "currency");
        requireText(request.counterpartyMsisdn(), "counterpartyMsisdn");
        if (request.amount() == null) {
            throw invalid("amount is required");
        }

        Capability operation;
        try {
            operation = Capability.valueOf(request.operation().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("operation must be COLLECT or DISBURSE, was '" + request.operation() + "'");
        }
        if (operation != Capability.COLLECT && operation != Capability.DISBURSE) {
            throw invalid("this endpoint creates COLLECT or DISBURSE payments, not " + operation);
        }

        Currency currency;
        try {
            currency = Currency.valueOf(request.currency().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("currency '" + request.currency() + "' is not one Nkap counts in");
        }

        long minorUnits;
        try {
            minorUnits = request.amount().longValueExact();
        } catch (ArithmeticException e) {
            throw invalid("amount " + request.amount() + " does not fit a minor-unit count");
        }
        if (minorUnits <= 0) {
            throw invalid("amount must be a positive number of minor units, was " + minorUnits);
        }

        try {
            return new PaymentIntent(operation, Money.of(minorUnits, currency),
                    request.counterpartyMsisdn().strip(), request.payerMessage(), request.payeeNote(), Map.of());
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    // --- rendering and the stored envelope ------------------------------------

    private record Rendered(int status, String body) {}

    /** The response envelope kept for replay: the status and the exact body. */
    private record StoredResponse(int status, String body) {}

    private Rendered render(Payment payment) {
        int status = payment.state().needsResolution() ? HttpStatus.ACCEPTED.value() : HttpStatus.CREATED.value();
        return new Rendered(status, write(PaymentResponse.of(payment)));
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

    private String canonical(CreatePaymentRequest request) {
        return write(request);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST,
                "The request is not valid", detail);
    }
}
