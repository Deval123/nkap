package dev.nkap.simulator;

import dev.nkap.simulator.scenario.SubmitBehaviour;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * An operator error body, in MTN's shape: {@code {"message": …, "code": …}}.
 *
 * <p>Every error the simulator answers an <em>operator</em> call with goes through here, so
 * an adapter can always read a {@code code} from a non-2xx response — {@code message} is
 * prose for a human and must never be parsed (see {@code docs/providers/mtn.md}). The
 * scenario control plane under {@code /_nkap} deliberately does not use this: it does not
 * imitate MTN, and a contributor debugging a malformed scenario is better served by its own
 * field-naming 400.
 *
 * <p>Two of the codes here are observed against the real sandbox and recorded in
 * {@code docs/providers/mtn.md}: {@code RESOURCE_ALREADY_EXIST} on a reused
 * {@code X-Reference-Id}, and {@code RESOURCE_NOT_FOUND} on a reference never submitted.
 * The rest are <strong>chosen by this simulator</strong> from MTN's documented vocabulary
 * (ADR 0004) as plausible defaults — {@link #INVALID_REFERENCE_ID} is not an MTN code at
 * all, because no observation records what MTN answers to a malformed header. A test that
 * needs a specific code should declare one rather than lean on these, which is what
 * {@link SubmitBehaviour#code()} is for.
 */
public record MtnErrorResponse(String message, String code) {

    /** Not an MTN code: the simulator's own, for a missing or malformed {@code X-Reference-Id}. */
    public static final String INVALID_REFERENCE_ID = "INVALID_REFERENCE_ID";

    /** Observed: a second POST with an {@code X-Reference-Id} already used. */
    public static MtnErrorResponse duplicateReference() {
        return new MtnErrorResponse("Duplicated reference id. Creation of resource failed.",
            "RESOURCE_ALREADY_EXIST");
    }

    /** Observed: a GET on a reference the operator has never seen. */
    public static MtnErrorResponse notFound() {
        return new MtnErrorResponse("Requested resource was not found.", "RESOURCE_NOT_FOUND");
    }

    /** A missing or malformed {@code X-Reference-Id}. {@code detail} says which. */
    public static MtnErrorException invalidReference(String detail) {
        return new MtnErrorException(HttpStatus.BAD_REQUEST,
            new MtnErrorResponse(detail, INVALID_REFERENCE_ID));
    }

    /**
     * The answer to a submission the scenario says must fail. The status comes from the
     * outcome; the code is the one the scenario declared, or this outcome's default when it
     * declared none. Only the three failing outcomes are mapped — {@code ACCEPT} has no
     * error body and {@code NO_RESPONSE} has no response at all.
     */
    public static ResponseEntity<MtnErrorResponse> forSubmit(SubmitBehaviour behaviour) {
        MtnErrorResponse body = switch (behaviour.outcome()) {
            case CONFLICT -> duplicateReference();
            case BAD_REQUEST -> new MtnErrorResponse("The operator refused the request.", "NOT_ALLOWED");
            case SERVER_ERROR -> new MtnErrorResponse(
                "The operator could not process the request.", "INTERNAL_PROCESSING_ERROR");
            case ACCEPT, NO_RESPONSE -> throw new IllegalArgumentException(
                behaviour.outcome() + " is not an error outcome");
        };
        String declared = behaviour.code();
        if (declared != null && !declared.isBlank()) {
            body = new MtnErrorResponse(body.message(), declared);
        }
        return ResponseEntity.status(statusFor(behaviour)).body(body);
    }

    private static HttpStatus statusFor(SubmitBehaviour behaviour) {
        return switch (behaviour.outcome()) {
            case CONFLICT -> HttpStatus.CONFLICT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case ACCEPT, NO_RESPONSE -> throw new IllegalArgumentException(
                behaviour.outcome() + " is not an error outcome");
        };
    }
}
