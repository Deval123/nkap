package dev.nkap.server.web;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * An error the API answers with, carrying everything the {@code problem+json} response
 * needs. Thrown from the controller and the service; turned into a {@link org.springframework.http.ProblemDetail}
 * in one place, {@link ApiExceptionHandler}.
 */
public final class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;

    public ApiException(HttpStatus status, URI type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }
}
