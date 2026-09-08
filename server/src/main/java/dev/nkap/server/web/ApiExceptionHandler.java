package dev.nkap.server.web;

import java.util.NoSuchElementException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error, as {@code application/problem+json} (RFC 7807), from one place. Controllers
 * throw {@link ApiException}; the framework's own binding failures are mapped here too, so
 * a client sees the same envelope whatever went wrong.
 *
 * <p>Highest precedence so these mappings win over Boot's default problem-detail advice,
 * which would otherwise answer a malformed body with a bare {@code about:blank} type.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "The request body could not be read",
                "Send a JSON object with an integer amount in minor units. "
                        + e.getMostSpecificCause().getMessage(),
                ProblemTypes.MALFORMED_REQUEST);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail onNoAdapter(NoSuchElementException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "The provider is not configured",
                e.getMessage(), ProblemTypes.PROVIDER_NOT_CONFIGURED);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, java.net.URI type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(type);
        return problem;
    }
}
