package dev.nkap.simulator;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The MTN MoMo Collections {@code requesttopay} surface, reproduced closely
 * enough to exercise a client's timeout and idempotency handling:
 *
 * <ul>
 *   <li>{@code X-Reference-Id} is a client-supplied UUID and the idempotency
 *       key. A second POST with the same one is a 409, never a second request.</li>
 *   <li>A missing or malformed {@code X-Reference-Id} is a 400.</li>
 *   <li>The POST returns 202 with an <em>empty</em> body: the status is only
 *       ever available through the GET. Reproducing that gap is the whole point
 *       of the simulator.</li>
 *   <li>A GET on an unknown reference is a 404.</li>
 * </ul>
 */
@RestController
public class RequestToPayController {

    private final CollectionRequestStore store;

    RequestToPayController(CollectionRequestStore store) {
        this.store = store;
    }

    @PostMapping("/collection/v1_0/requesttopay")
    public ResponseEntity<Void> requestToPay(
            @RequestHeader(value = "X-Reference-Id", required = false) String referenceId,
            @RequestBody(required = false) Map<String, Object> body) {

        String reference = requireUuid(referenceId);
        String amount = body == null ? null : String.valueOf(body.get("amount"));
        String currency = body == null ? null : String.valueOf(body.get("currency"));

        if (!store.record(reference, amount, currency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "X-Reference-Id already used");
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/collection/v1_0/requesttopay/{referenceId}")
    public Map<String, String> status(@PathVariable String referenceId) {
        return store.poll(canonical(referenceId))
            .map(status -> Map.of("status", status.name()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "unknown reference"));
    }

    private static String requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "X-Reference-Id header is required");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "X-Reference-Id must be a UUID");
        }
    }

    /**
     * The reference is a UUID and its textual form is not significant: MTN
     * clients send it upper- or lower-case interchangeably. Store and look it up
     * in one canonical form so a POST and a later GET agree. A GET value that is
     * not a UUID is returned untouched and falls through to 404.
     */
    private static String canonical(String reference) {
        try {
            return UUID.fromString(reference).toString();
        } catch (IllegalArgumentException e) {
            return reference;
        }
    }
}
