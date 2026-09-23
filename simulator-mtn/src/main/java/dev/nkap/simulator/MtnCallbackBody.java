package dev.nkap.simulator;

import dev.nkap.simulator.scenario.MomoStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The body of an MTN callback: the {@code referenceId} it is about, its {@code status},
 * the submitted {@code amount} and {@code currency} when the submission carried them, a
 * {@code financialTransactionId}, and a {@code reason} on a {@code FAILED} one.
 */
@Component
class MtnCallbackBody implements CallbackBody<MomoStatus> {

    @Override
    public Map<String, Object> render(String paymentId, String amount, String currency, MomoStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceId", paymentId);
        body.put("status", status.name());
        if (amount != null) {
            body.put("amount", amount);
        }
        if (currency != null) {
            body.put("currency", currency);
        }
        body.put("financialTransactionId", financialTransactionId(paymentId));
        if (status == MomoStatus.FAILED) {
            body.put("reason", "SIMULATED_FAILURE");
        }
        return body;
    }

    /**
     * A stand-in for MTN's numeric transaction id. Derived from the reference so
     * a scenario stays deterministic across runs (ADR 0002), not random.
     */
    private static String financialTransactionId(String reference) {
        return Long.toString(Integer.toUnsignedLong(reference.hashCode()));
    }
}
