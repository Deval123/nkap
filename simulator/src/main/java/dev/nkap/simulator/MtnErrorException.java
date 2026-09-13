package dev.nkap.simulator;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * An operator error thrown from deep inside a handler — a reused reference, an unknown one,
 * a malformed header — carrying the status and the MTN-shaped body it must be answered with.
 *
 * <p>Deliberately not Spring's {@code ResponseStatusException}: that one renders the
 * framework's own error envelope, which is precisely the fiction issue #26 removes. It is
 * rendered by {@link MtnErrorHandler}.
 */
public class MtnErrorException extends RuntimeException {

    private final transient HttpStatus status;
    private final transient MtnErrorResponse body;

    public MtnErrorException(HttpStatus status, MtnErrorResponse body) {
        super(body.code() + ": " + body.message());
        this.status = status;
        this.body = body;
    }

    public ResponseEntity<MtnErrorResponse> asResponse() {
        return ResponseEntity.status(status).body(body);
    }
}
