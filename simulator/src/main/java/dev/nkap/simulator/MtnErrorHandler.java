package dev.nkap.simulator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link MtnErrorException} as the operator error body it carries. Scoped to the
 * exception type rather than to a set of controllers: only the operator surfaces throw it,
 * and the control plane's own 400 is handled inside {@link ControlPlaneController}.
 */
@RestControllerAdvice
class MtnErrorHandler {

    @ExceptionHandler(MtnErrorException.class)
    ResponseEntity<MtnErrorResponse> onOperatorError(MtnErrorException e) {
        return e.asResponse();
    }
}
