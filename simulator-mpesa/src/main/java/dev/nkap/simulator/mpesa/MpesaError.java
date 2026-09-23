package dev.nkap.simulator.mpesa;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * An M-Pesa error body: {@code {"errorCode": …, "errorMessage": …}}, the two fields the page
 * records for each error it observed. Anything else Safaricom may put beside them is not
 * recorded, and not invented here.
 *
 * <p>Two errors are <strong>observed</strong>: {@code 400.002.02} "Bad Request - Invalid
 * CallBackURL" at submission (2026-09-18), and {@code 500.001.1001} "The transaction does not
 * Exist" on a query (2026-09-18). Every other code or message this face answers with is
 * <strong>modelled</strong> and says so where it is chosen.
 */
public record MpesaError(String errorCode, String errorMessage) {

    /** Observed, 2026-09-18, for a {@code CheckoutRequestID} Safaricom did not recognise. */
    public static MpesaError transactionDoesNotExist() {
        return new MpesaError("500.001.1001", "The transaction does not Exist");
    }

    public ResponseEntity<MpesaError> answer(HttpStatus status) {
        return ResponseEntity.status(status).body(this);
    }
}
