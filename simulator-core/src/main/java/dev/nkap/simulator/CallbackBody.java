package dev.nkap.simulator;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * How a callback is said on the wire, in the face's own shape: its body, its content type
 * and any headers. When a callback is due, where it is sent and what is recorded about it
 * are {@link CallbackDispatcher}'s; what it says, and how, is the operator's.
 *
 * @param <S> the face's status
 */
public interface CallbackBody<S> {

    /**
     * Everything a face may need to say one delivery.
     *
     * @param paymentId  the payment the callback names — the submitted one, or one never
     *                   submitted when the scenario asks for that
     * @param amount     the submitted amount, or {@code null} when the submission carried none
     * @param currency   the submitted currency, or {@code null} when the submission carried none
     * @param status     what the callback reports
     * @param submission what the face chose to keep from the submission for its callbacks,
     *                   carried by the core without reading it; empty when it kept nothing
     */
    record Callback<S>(String paymentId, String amount, String currency, S status, Map<String, String> submission) {

        public Callback {
            submission = submission != null ? Map.copyOf(submission) : Map.of();
        }
    }

    /** The JSON body for one delivery. */
    Map<String, Object> render(Callback<S> callback);

    /** The content type the body is sent with. */
    default MediaType contentType() {
        return MediaType.APPLICATION_JSON;
    }

    /** Headers the operator sends with the body, beyond the content type. None by default. */
    default HttpHeaders headers(Callback<S> callback) {
        return new HttpHeaders();
    }
}
