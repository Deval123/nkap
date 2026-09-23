package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.CallbackBody;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * An STK callback, as <strong>observed</strong> on 2026-09-18 and 2026-09-22:
 * {@code {"Body":{"stkCallback":{MerchantRequestID, CheckoutRequestID, ResultCode, ResultDesc}}}},
 * {@code ResultCode} a JSON number, sent as {@code application/json;charset=UTF-8} with a
 * {@code BusinessShortCode} header carrying the submission's shortcode. Nothing the caller
 * chose is in it, under any name. There is no signature and no credential, on either run.
 *
 * <p>A successful payment's callback is <strong>modelled</strong>: it carries
 * {@link MpesaResult#SUCCESS}'s code and description and nothing more. The real one is
 * understood to carry {@code CallbackMetadata} — a receipt number, the payer's MSISDN — but
 * the page lists exactly that as unknown, so this face leaves it out rather than invent
 * its shape.
 */
@Component
class MpesaCallbackBody implements CallbackBody<MpesaResult> {

    /** The key the face keeps the submission's {@code BusinessShortCode} under for its callbacks. */
    static final String BUSINESS_SHORT_CODE = "BusinessShortCode";

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    @Override
    public Map<String, Object> render(Callback<MpesaResult> callback) {
        Map<String, Object> stkCallback = new LinkedHashMap<>();
        stkCallback.put("MerchantRequestID", MpesaPaymentIdentity.merchantRequestId(callback.paymentId()));
        stkCallback.put("CheckoutRequestID", callback.paymentId());
        stkCallback.put("ResultCode", callback.status().code());
        stkCallback.put("ResultDesc", callback.status().callbackDescription());
        return Map.of("Body", Map.of("stkCallback", stkCallback));
    }

    @Override
    public MediaType contentType() {
        return JSON_UTF8;
    }

    @Override
    public HttpHeaders headers(Callback<MpesaResult> callback) {
        HttpHeaders headers = new HttpHeaders();
        String shortCode = callback.submission().get(BUSINESS_SHORT_CODE);
        if (shortCode != null) {
            headers.set(BUSINESS_SHORT_CODE, shortCode);
        }
        return headers;
    }
}
