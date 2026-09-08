package dev.nkap.provider.mtn;

import static dev.nkap.core.payment.PaymentState.EXPIRED;
import static dev.nkap.core.payment.PaymentState.FAILED;
import static dev.nkap.core.payment.PaymentState.PENDING;
import static dev.nkap.core.payment.PaymentState.SUCCEEDED;
import static dev.nkap.core.payment.PaymentState.UNKNOWN;

import dev.nkap.core.payment.PaymentState;
import java.util.Map;
import java.util.Set;

/**
 * MTN's status and error codes, mapped to {@link PaymentState}. One place, as data.
 *
 * <p>The table is closed: every code MTN documents (ADR 0004) has an entry, and
 * <strong>anything absent maps to {@link PaymentState#UNKNOWN}</strong>. A code we do not
 * recognise is not a failure we can assert — that conservatism is the reason this project
 * exists.
 *
 * <p>Three rows carry the weight. {@code SERVICE_UNAVAILABLE} and
 * {@code INTERNAL_PROCESSING_ERROR} are the operator saying <em>its own</em> system
 * broke — which says nothing about where the money went, so {@code UNKNOWN}, not
 * {@code FAILED}. {@code RESOURCE_NOT_FOUND} on a query means MTN has never seen the
 * reference; since Nkap persists it before calling, that is either "never arrived" or
 * "not visible yet", and one response cannot tell them apart — the reconciler does,
 * after its window.
 */
final class MtnStatusMap {

    /** Codes from ADR 0004. A test asserts every one of these is a key of {@link #TABLE}. */
    static final Set<String> DOCUMENTED_CODES = Set.of(
            "SUCCESSFUL",
            "PENDING",
            "PAYER_NOT_FOUND",
            "PAYEE_NOT_FOUND",
            "NOT_ENOUGH_FUNDS",
            "PAYER_LIMIT_REACHED",
            "APPROVAL_REJECTED",
            "EXPIRED",
            "INVALID_CURRENCY",
            "NOT_ALLOWED",
            "INVALID_CALLBACK_URL_HOST",
            "SERVICE_UNAVAILABLE",
            "INTERNAL_PROCESSING_ERROR",
            "RESOURCE_NOT_FOUND");

    private static final Map<String, PaymentState> TABLE = Map.ofEntries(
            Map.entry("SUCCESSFUL", SUCCEEDED),
            Map.entry("PENDING", PENDING),
            Map.entry("EXPIRED", EXPIRED),
            // A bare FAILED with no recognised reason is still the operator saying "no".
            Map.entry("FAILED", FAILED),
            Map.entry("PAYER_NOT_FOUND", FAILED),
            Map.entry("PAYEE_NOT_FOUND", FAILED),
            Map.entry("NOT_ENOUGH_FUNDS", FAILED),
            Map.entry("PAYER_LIMIT_REACHED", FAILED),
            Map.entry("APPROVAL_REJECTED", FAILED),
            Map.entry("INVALID_CURRENCY", FAILED),
            Map.entry("NOT_ALLOWED", FAILED),
            Map.entry("INVALID_CALLBACK_URL_HOST", FAILED),
            Map.entry("SERVICE_UNAVAILABLE", UNKNOWN),
            Map.entry("INTERNAL_PROCESSING_ERROR", UNKNOWN),
            Map.entry("RESOURCE_NOT_FOUND", UNKNOWN));

    private MtnStatusMap() {
    }

    /**
     * The state for a query response or a callback. {@code reason} is consulted before
     * {@code status}, so a {@code FAILED} that carries {@code SERVICE_UNAVAILABLE} —
     * the operator's system failing mid-answer — comes out {@code UNKNOWN}, not
     * {@code FAILED}. {@code errorCode} is the {@code code} field of a non-200 body.
     * Any token not in the table is {@link PaymentState#UNKNOWN}.
     */
    static PaymentState stateFor(String status, String reason, String errorCode) {
        String key = firstNonBlank(reason, status, errorCode);
        return TABLE.getOrDefault(key, UNKNOWN);
    }

    static boolean isKnown(String code) {
        return code != null && TABLE.containsKey(code);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
