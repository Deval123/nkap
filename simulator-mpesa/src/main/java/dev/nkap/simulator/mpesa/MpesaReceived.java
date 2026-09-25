package dev.nkap.simulator.mpesa;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * What the M-Pesa face received, recorded and never checked: the Consumer Key and Secret a token
 * request carried, and the fields a submission's {@code Password} is computed from. Read at
 * {@code GET /_nkap/received}.
 *
 * <p><strong>Recording is not checking.</strong> This face plays only what was observed of
 * Safaricom, and Safaricom's answer to a wrong Consumer Key, or to a {@code Password} computed from
 * a wrong passkey, was never observed. So nothing here judges a value: a request is accepted or
 * refused exactly as it would be without this class, and a wrong Consumer Key is still accepted.
 * Recording invents no behaviour; it only lets a test see what arrived, such as a rotated
 * credential reaching the operator. Turning it into a check would model a behaviour nobody saw,
 * which is the one thing this simulator refuses to do.
 *
 * <p>The most recent of each request and a count of each, not a history: a record that grew with
 * every request would be its own defect in a test that loops. Both are cleared by
 * {@code DELETE /_nkap/state}, with the rest of the simulator's state, and left alone by
 * {@code DELETE /_nkap/scenarios}, which resets only what is declared.
 *
 * <p><strong>What this exposes.</strong> The Consumer Key and Secret are held and served in the
 * clear, and the {@code Password} is computed from a passkey anyone can read back from it. That is
 * acceptable here for two reasons. The gateway already sends all of it to whatever its
 * {@code base-url} names, so recording it where it arrives exposes nothing the request did not.
 * And {@code /_nkap} is the test control plane of a simulator that is never a face for real
 * credentials. It follows that a simulator reached by a deployment holding real credentials would
 * hand them to anyone who can reach {@code /_nkap}. Never point one at it.
 *
 * <p>Nothing is logged here, and both records mask their credentials in {@code toString()}, as
 * every record holding a credential does in this repository.
 */
@Component
public class MpesaReceived {

    /**
     * A token request's HTTP Basic credentials, decoded. Both null when the request carried no
     * Basic header this could decode: the token endpoint does not require one, so that is recorded
     * too.
     */
    public record TokenRequest(String consumerKey, String consumerSecret) {

        @Override
        public String toString() {
            return "TokenRequest[consumerKey=***, consumerSecret=***]";
        }
    }

    /**
     * A submission's {@code BusinessShortCode}, {@code Timestamp} and {@code Password}, as they
     * arrived: enough to recompute {@code base64(BusinessShortCode + passkey + Timestamp)} and
     * compare. A field that was absent is null.
     */
    public record Submission(String businessShortCode, String timestamp, String password) {

        @Override
        public String toString() {
            return "Submission[businessShortCode=" + businessShortCode + ", timestamp=" + timestamp
                    + ", password=***]";
        }
    }

    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicReference<TokenRequest> lastTokenRequest = new AtomicReference<>();
    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicReference<Submission> lastSubmission = new AtomicReference<>();

    void tokenRequested(String authorization) {
        lastTokenRequest.set(decodeBasic(authorization));
        tokenRequests.incrementAndGet();
    }

    void submissionArrived(Map<String, Object> body) {
        lastSubmission.set(new Submission(text(body, "BusinessShortCode"), text(body, "Timestamp"),
                text(body, "Password")));
        submissions.incrementAndGet();
    }

    int tokenRequests() {
        return tokenRequests.get();
    }

    TokenRequest lastTokenRequest() {
        return lastTokenRequest.get();
    }

    int submissions() {
        return submissions.get();
    }

    Submission lastSubmission() {
        return lastSubmission.get();
    }

    void clear() {
        tokenRequests.set(0);
        lastTokenRequest.set(null);
        submissions.set(0);
        lastSubmission.set(null);
    }

    /** RFC 7617: the user-id is everything before the first colon, the password everything after. */
    private static TokenRequest decodeBasic(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return new TokenRequest(null, null);
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return new TokenRequest(null, null);
        }
        int colon = decoded.indexOf(':');
        return colon < 0 ? new TokenRequest(null, null)
                : new TokenRequest(decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    /** A field as the text it was sent as, whatever its JSON type: BusinessShortCode arrives as a number. */
    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
