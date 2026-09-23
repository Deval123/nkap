package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.Product;
import dev.nkap.simulator.Submissions;
import dev.nkap.simulator.Submissions.Submission;
import dev.nkap.simulator.scenario.SubmitBehaviour;
import dev.nkap.simulator.scenario.SubmitOutcome;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * Safaricom Daraja's STK Push: the submission, {@code POST /mpesa/stkpush/v1/processrequest},
 * and its status query, {@code POST /mpesa/stkpushquery/v1/query}, both bearer-authenticated
 * — <strong>observed</strong>, 2026-09-18. The controller decides nothing about payment
 * behaviour: it hands the submission to {@link Submissions}, asks {@link MpesaScenarioEngine}
 * what a query answers, and says it the way Safaricom does.
 *
 * <p>Neither request is validated beyond what is needed to act on it. Safaricom refused one
 * {@code CallBackURL} with {@code 400.002.02} and accepted others, and the page cannot say
 * which rule the refusal applied; nor is {@code Password} checked against a passkey. A test
 * that wants a refusal declares one. Collections only: no B2C.
 */
@RestController
class StkPushController {

    private final Submissions<MpesaScenario, MpesaResult> submissions;
    private final MpesaScenarioEngine engine;
    private final MpesaTokens tokens;

    StkPushController(Submissions<MpesaScenario, MpesaResult> submissions, MpesaScenarioEngine engine,
                      MpesaTokens tokens) {
        this.submissions = submissions;
        this.engine = engine;
        this.tokens = tokens;
    }

    /**
     * The submission. The request's fields are read as Safaricom's documented example types
     * them — {@code BusinessShortCode} a JSON number, {@code Amount} and {@code PartyB}
     * strings — but accepted whichever type they arrive as, since a simulator that refused
     * the other would be checking something the page never saw Safaricom check.
     *
     * <p>{@code AccountReference} is matched against the scenario rules and then forgotten:
     * it is echoed in nothing, observed. {@code PhoneNumber} is the MSISDN a rule matches and
     * the identity encodes; {@code Amount} is matched as the string it was sent as.
     */
    @PostMapping("/mpesa/stkpush/v1/processrequest")
    public Object processRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        tokens.require(authorization);

        Map<String, String> callbackData = new LinkedHashMap<>();
        String shortCode = field(body, "BusinessShortCode");
        if (shortCode != null) {
            callbackData.put(MpesaCallbackBody.BUSINESS_SHORT_CODE, shortCode);
        }

        // Submit first — which schedules the callbacks as the scenario resolves — and only
        // then apply the submit delay, so that a callback declared with after:PT0S can reach
        // the client before the submission is answered (issue #6).
        Submission<MpesaScenario> submission = submissions.submit(Product.COLLECTIONS,
                field(body, "AccountReference"), field(body, "PhoneNumber"), field(body, "Amount"), null,
                field(body, "CallBackURL"), callbackData);

        SubmitBehaviour onSubmit = submission.scenario().onSubmit();
        if (onSubmit.outcome() == SubmitOutcome.NO_RESPONSE) {
            return neverAnswer();
        }
        sleep(onSubmit.delay());
        return switch (onSubmit.outcome()) {
            case ACCEPT -> ResponseEntity.ok(accepted(submission.paymentId()));
            case BAD_REQUEST, SERVER_ERROR -> refusal(onSubmit);
            case NO_RESPONSE -> throw new IllegalStateException("handled above");
            // Refused when declared: this face's operator does not deduplicate, so the core's
            // control plane never lets a scenario with CONFLICT reach here.
            case CONFLICT -> throw new IllegalStateException("CONFLICT is refused at declaration for this face");
        };
    }

    /**
     * The status query, keyed by {@code CheckoutRequestID} alone. An identity Safaricom never
     * minted answers {@code HTTP 500}, {@code 500.001.1001} — observed. A known one answers
     * the scenario's next {@code onQuery} entry: {@code HTTP 200} with its {@code ResultCode}
     * and {@code ResultDesc}, or the same {@code 500} when that entry declares one.
     */
    @PostMapping("/mpesa/stkpushquery/v1/query")
    public ResponseEntity<?> query(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        tokens.require(authorization);

        String checkoutRequestId = field(body, "CheckoutRequestID");
        MpesaQueryBehaviour behaviour = checkoutRequestId == null ? null
                : engine.nextQueryBehaviour(Product.COLLECTIONS, checkoutRequestId).orElse(null);
        if (behaviour == null) {
            return MpesaError.transactionDoesNotExist().answer(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        sleep(behaviour.delay());
        if (behaviour.error500()) {
            // Observed 2026-09-23 with this code for an existing reference; that run did not
            // record the message, so the one observed with the same code on 2026-09-18 is used.
            return MpesaError.transactionDoesNotExist().answer(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return ResponseEntity.ok(queryAnswer(checkoutRequestId, behaviour.status()));
    }

    /**
     * {@code MerchantRequestID}, {@code CheckoutRequestID}, {@code ResponseCode},
     * {@code ResponseDescription}, {@code CustomerMessage} — the fields are
     * <strong>observed</strong>, 2026-09-18. Their values are not all recorded:
     * {@code ResponseCode} is recorded as {@code 0} but not whether as a number or a string,
     * and the two descriptions are not recorded at all. A string {@code "0"} and the prose
     * below are <strong>modelled</strong>.
     */
    private static Map<String, Object> accepted(String checkoutRequestId) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("MerchantRequestID", MpesaPaymentIdentity.merchantRequestId(checkoutRequestId));
        answer.put("CheckoutRequestID", checkoutRequestId);
        answer.put("ResponseCode", "0");
        answer.put("ResponseDescription", "Success. Request accepted for processing");
        answer.put("CustomerMessage", "Success. Request accepted for processing");
        return answer;
    }

    /**
     * {@code ResultCode} and {@code ResultDesc} are <strong>observed</strong> on the query;
     * the two identifiers beside them are <strong>modelled</strong>, echoed so that an answer
     * names the payment it is about. {@code ResultCode} is a JSON number here, as it is in the
     * observed callback; its type on the query channel is not recorded.
     */
    private static Map<String, Object> queryAnswer(String checkoutRequestId, MpesaResult result) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("MerchantRequestID", MpesaPaymentIdentity.merchantRequestId(checkoutRequestId));
        answer.put("CheckoutRequestID", checkoutRequestId);
        answer.put("ResultCode", result.code());
        answer.put("ResultDesc", result.queryDescription());
        return answer;
    }

    /**
     * A submission the scenario says must fail. {@code BAD_REQUEST} answers the one
     * submission error observed, {@code 400.002.02} "Bad Request - Invalid CallBackURL"
     * (2026-09-18) — as a default for every refusal it is <strong>modelled</strong>.
     * {@code SERVER_ERROR} answers {@code HTTP 500} with {@code 500.001.1001}, the one server
     * code observed, though only ever on a query: <strong>modelled</strong>, and so is its
     * message. A declared {@code code} replaces the default in each case. {@code CONFLICT}
     * never gets here: M-Pesa refuses no repeated reference, observed, and the control plane
     * refuses a scenario that declares it.
     */
    private static ResponseEntity<MpesaError> refusal(SubmitBehaviour behaviour) {
        MpesaError error = switch (behaviour.outcome()) {
            case BAD_REQUEST -> new MpesaError("400.002.02", "Bad Request - Invalid CallBackURL");
            case SERVER_ERROR -> new MpesaError("500.001.1001", "Internal Server Error");
            case ACCEPT, NO_RESPONSE, CONFLICT -> throw new IllegalArgumentException(
                    behaviour.outcome() + " is not a refusal this face answers");
        };
        if (behaviour.code() != null && !behaviour.code().isBlank()) {
            error = new MpesaError(behaviour.code(), error.errorMessage());
        }
        HttpStatus status = switch (behaviour.outcome()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case ACCEPT, NO_RESPONSE, CONFLICT -> throw new IllegalArgumentException(
                    behaviour.outcome() + " is not a refusal this face answers");
        };
        return error.answer(status);
    }

    /** The {@code NO_RESPONSE} outcome: accepted, then silent. Never a sleep on the request thread. */
    private static DeferredResult<ResponseEntity<Void>> neverAnswer() {
        return new DeferredResult<>(Duration.ofHours(1).toMillis());
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
