package dev.nkap.server.web;

import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.server.provider.NoAdapterConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error, as {@code application/problem+json} (RFC 7807), from one place. Controllers
 * throw {@link ApiException}; the framework's own binding failures are mapped here too, so
 * a client sees the same envelope whatever went wrong.
 *
 * <p>Highest precedence so these mappings win over Boot's default problem-detail advice,
 * which would otherwise answer a malformed body with a bare {@code about:blank} type.
 *
 * <p>What the client is told is written here, not borrowed. A JSON library's phrasing and
 * the server's own configuration go to the log; the response carries a stable {@code type}
 * and a sentence this project chose.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail onApiException(ApiException e) {
        return problem(e.status(), e.title(), e.getMessage(), e.type());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail onMissingHeader(MissingRequestHeaderException e) {
        if ("Idempotency-Key".equalsIgnoreCase(e.getHeaderName())) {
            return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key is required",
                    "POST /payments requires an Idempotency-Key header so a retry cannot pay twice.",
                    ProblemTypes.MISSING_IDEMPOTENCY_KEY);
        }
        return problem(HttpStatus.BAD_REQUEST, "A required header is missing",
                e.getMessage(), ProblemTypes.INVALID_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail onMissingParameter(MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST, "A required query parameter is missing",
                e.getParameterName() + " is required.", ProblemTypes.INVALID_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        // The cause can quote fragments of the submitted body — an MSISDN, say — and its
        // wording is a JSON library's, not our contract. Keep it in the log; answer the
        // client with our own sentence.
        log.debug("request body could not be read", e);
        return problem(HttpStatus.BAD_REQUEST, "The request body could not be read",
                "Send a JSON object with an integer amount in minor units.",
                ProblemTypes.MALFORMED_REQUEST);
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    ProblemDetail onOperatorDidNotAnswer(ProviderUnavailableException e) {
        // One place for every live operator read (GET /balance, GET /account-holders/{msisdn})
        // to answer this: not known, not failed, and never a bare 500 — the read-side
        // equivalent of a submission that does not answer being a 202, not an error.
        log.info("a live operator read did not answer: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "The operator did not answer",
                "This reads live from the operator, and it did not answer. That is not a "
                        + "failed read -- it is not known, and the same request can be retried.",
                ProblemTypes.OPERATOR_DID_NOT_ANSWER);
    }

    @ExceptionHandler(NoAdapterConfiguredException.class)
    ProblemDetail onNoAdapter(NoAdapterConfiguredException e) {
        // Which providers are configured is internal detail; it stays in the log.
        log.error("a request routed to a provider with no configured adapter", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "The provider is not configured",
                "This request routed to a provider the server has no adapter for. That is a "
                        + "server misconfiguration, not a problem with the request.",
                ProblemTypes.PROVIDER_NOT_CONFIGURED);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, java.net.URI type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(type);
        return problem;
    }
}
