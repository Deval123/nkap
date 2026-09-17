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

    /**
     * Codes from ADR 0004. A test asserts every one of these is a key of {@link #TABLE}, and
     * a separate test (issue #92) asserts {@code docs/providers/mtn.md} marks exactly these
     * codes, and no others, as documented by MTN.
     */
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

    /**
     * Package-private, not private, so {@code MtnStatusMappingDocTest} (issue #92) can read
     * it directly and check {@code docs/providers/mtn.md}'s table against it in both
     * directions, the same discipline {@code CurrencyTest} applies to {@code Currency} and
     * {@code ConfigurationReferenceTest} applies to {@code application.yml}. Widening
     * visibility for a same-package test is not a change in what this class exposes to
     * anything that actually calls it — {@link #stateFor} and {@link #isKnown} are still the
     * only way another package reaches this data.
     */
    static final Map<String, PaymentState> TABLE = Map.ofEntries(
            Map.entry("SUCCESSFUL", SUCCEEDED),
            Map.entry("PENDING", PENDING),
            // Not in ADR 0004 / DOCUMENTED_CODES: MTN's own Pending test MSISDN answers this,
            // observed once, against one MSISDN, in one sandbox run (issue #117; see
            // docs/providers/mtn.md, "MTN's published test MSISDNs"). PENDING over UNKNOWN
            // because both are non-terminal and both get reconciled — the choice is about what
            // a merchant is told, not escalation or the ledger — and it inherits PENDING's own
            // non-conclusiveness for free: alone it is PENDING, beside an inconclusive reason
            // it is UNKNOWN, same as any other PENDING.
            Map.entry("CREATED", PENDING),
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
     * The state for a query response or a callback. Both {@code status} and {@code reason}
     * are read, not just one: whichever names a <strong>conclusive</strong> outcome —
     * {@code SUCCEEDED}, {@code FAILED} or {@code EXPIRED}; not {@code PENDING}, which is a
     * real answer but never a final one — wins. When both do and they disagree, the more
     * specific of the two wins over the more general one — {@code EXPIRED} over a bare
     * {@code FAILED}, MTN's own shape for a request that timed out. Two conclusive readings
     * that disagree about the outcome itself, rather than one refining the other, are a
     * contradiction, not a verdict: {@link PaymentState#UNKNOWN}, never a guess at which half
     * to believe.
     *
     * <p>A {@code FAILED} that carries {@code SERVICE_UNAVAILABLE} — the operator's own
     * system failing mid-answer — now comes out {@code FAILED}. {@code SERVICE_UNAVAILABLE}
     * alone still maps to {@link PaymentState#UNKNOWN}, because on its own it says nothing
     * about where the money went; but a {@code status} naming a real, conclusive verdict is
     * not "nothing" just because {@code reason} had nothing to add to it. A {@code PENDING}
     * paired with the same {@code SERVICE_UNAVAILABLE} is different: {@code PENDING} is not
     * conclusive, so it does not survive an inconclusive {@code reason} the way a final
     * verdict does — if the operator's own system is having trouble, its claim that the
     * payment is merely still in flight is exactly as suspect as any other claim it might
     * make right now, and the answer is {@link PaymentState#UNKNOWN}. A lone
     * {@code PENDING}, with no {@code reason} at all, is unaffected and still
     * {@link PaymentState#PENDING} — {@code reason} arriving <strong>without</strong> a
     * {@code status} is, as ever, still {@link PaymentState#UNKNOWN}.
     *
     * <p>A token neither field's table has an entry for makes the whole answer
     * {@link PaymentState#UNKNOWN}, even when the other field names a recognised verdict — a
     * code this adapter has never seen is not license to trust the one it has.
     * {@code errorCode} is the {@code code} field of a non-200 body, consulted only when
     * neither {@code status} nor {@code reason} is present at all.
     */
    static PaymentState stateFor(String status, String reason, String errorCode) {
        if (isBlank(status) && isBlank(reason)) {
            return TABLE.getOrDefault(orEmpty(errorCode), UNKNOWN);
        }
        if (!isBlank(status) && !TABLE.containsKey(status)) {
            return UNKNOWN;
        }
        if (!isBlank(reason) && !TABLE.containsKey(reason)) {
            return UNKNOWN;
        }

        PaymentState statusState = isBlank(status) ? null : TABLE.get(status);
        PaymentState reasonState = isBlank(reason) ? null : TABLE.get(reason);
        boolean statusConclusive = isConclusive(statusState);
        boolean reasonConclusive = isConclusive(reasonState);

        if (statusConclusive && reasonConclusive) {
            if (statusState == reasonState) {
                return statusState;
            }
            if (refines(reasonState, statusState)) {
                return reasonState;
            }
            if (refines(statusState, reasonState)) {
                return statusState;
            }
            return UNKNOWN;
        }
        if (statusConclusive) {
            return statusState;
        }
        if (reasonConclusive) {
            return reasonState;
        }
        // Neither field named a conclusive verdict. A lone PENDING — no reason at all — is
        // still PENDING; paired with anything else present, even something as inconclusive
        // as SERVICE_UNAVAILABLE, it is not trustworthy enough to stand on its own.
        if (isBlank(reason) && statusState == PENDING) {
            return PENDING;
        }
        if (isBlank(status) && reasonState == PENDING) {
            return PENDING;
        }
        return UNKNOWN;
    }

    static boolean isKnown(String code) {
        return code != null && TABLE.containsKey(code);
    }

    /**
     * A final verdict: the payment will not be re-queried into a different outcome.
     * {@code PENDING} is deliberately excluded — it is a real answer, but never a final one,
     * so it must not out-rank, or be trusted alongside, an inconclusive companion reading the
     * way {@code SUCCEEDED}, {@code FAILED} and {@code EXPIRED} do.
     */
    private static boolean isConclusive(PaymentState state) {
        return state == SUCCEEDED || state == FAILED || state == EXPIRED;
    }

    /**
     * Whether {@code specific} is a more precise verdict within the same outcome as
     * {@code general}, rather than a different outcome altogether. Today this is only
     * {@code EXPIRED} refining {@code FAILED} — MTN's observed shape for a timed-out request
     * is {@code status: FAILED, reason: EXPIRED} — but the check does not assume which field
     * the more specific reading arrives in.
     */
    private static boolean refines(PaymentState specific, PaymentState general) {
        return specific == EXPIRED && general == FAILED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
