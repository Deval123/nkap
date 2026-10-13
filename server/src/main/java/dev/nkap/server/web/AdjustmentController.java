package dev.nkap.server.web;

import dev.nkap.server.adjustment.Adjustment;
import dev.nkap.server.adjustment.AdjustmentService;
import dev.nkap.server.auth.ApiCredential;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The five {@code /adjustments} routes (ADR 0017, issue #266): one key proposes, a different
 * one approves or rejects, and approval is the only thing that writes to the ledger.
 *
 * <p>Nothing is decided here. Every rule, including whether the feature is switched on at
 * all, is {@link AdjustmentService}'s, so a route and a test of the service cannot disagree.
 * That is also why a switched-off deployment answers {@code 501 adjustment-not-enabled} from
 * these routes and not a {@code 404}: the routes exist.
 *
 * <p>Authorisation is the key's, as on every other route: {@link ApiCredential#merchantId()}
 * scopes a read and {@link ApiCredential#admin()} widens it. There is no role and no scope,
 * and the "different key" rule is the service's comparison of two {@code keyId}s.
 */
@RestController
@RequestMapping("/adjustments")
class AdjustmentController {

    private final AdjustmentService adjustments;

    AdjustmentController(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @PostMapping
    ResponseEntity<AdjustmentResponse> propose(ApiCredential caller, @RequestBody(required = false) AdjustmentRequest body) {
        adjustments.ensureEnabled();
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST,
                    "The adjustment could not be accepted", "Send a JSON object describing the adjustment.");
        }
        Adjustment created = adjustments.propose(caller, body.toNewAdjustment());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/adjustments/" + created.id())
                .body(AdjustmentResponse.of(created));
    }

    @PostMapping("/{id}/approve")
    AdjustmentResponse approve(ApiCredential caller, @PathVariable String id,
                               @RequestBody(required = false) AdjustmentDecisionRequest body) {
        adjustments.ensureEnabled();
        UUID parsed = parse(id);
        return AdjustmentResponse.of(adjustments.approve(caller, parsed, reasonOf(body)));
    }

    @PostMapping("/{id}/reject")
    AdjustmentResponse reject(ApiCredential caller, @PathVariable String id,
                              @RequestBody(required = false) AdjustmentDecisionRequest body) {
        adjustments.ensureEnabled();
        UUID parsed = parse(id);
        return AdjustmentResponse.of(adjustments.reject(caller, parsed, reasonOf(body)));
    }

    @GetMapping
    List<AdjustmentResponse> list(ApiCredential caller) {
        return adjustments.list(caller).stream().map(AdjustmentResponse::of).toList();
    }

    @GetMapping("/{id}")
    AdjustmentResponse get(ApiCredential caller, @PathVariable String id) {
        adjustments.ensureEnabled();
        return AdjustmentResponse.of(adjustments.get(caller, parse(id)));
    }

    private static String reasonOf(AdjustmentDecisionRequest body) {
        return body == null ? "" : body.reason();
    }

    /** A path segment that is not a UUID names no adjustment; the answer is the same 404 as an unknown one. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.ADJUSTMENT_NOT_FOUND,
                    "No such adjustment", "'" + id + "' is not an adjustment id.");
        }
    }
}
