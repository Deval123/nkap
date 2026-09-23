package dev.nkap.provider.mpesa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.Capability;
import dev.nkap.provider.HolderStatus;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.Resolution;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * The M-Pesa adapter: Safaricom Daraja's STK Push, Collections only. The second
 * implementation of {@link ProviderAdapter}, and the first written against the contract
 * rather than alongside it, certified by the conformance kit by a maintainer with no
 * Safaricom account.
 *
 * <p>It translates and nothing more. Its only source is {@code docs/providers/m-pesa.md};
 * where that page records something as observed this class says so, and where it does not the
 * behaviour is labelled <strong>modelled</strong>. What makes it unlike MTN's adapter is what
 * the page observed:
 *
 * <ul>
 *   <li>Safaricom mints the payment's identity, the {@code CheckoutRequestID}, and returns it
 *       in the submission's answer. The reference Nkap chose travels as
 *       {@code AccountReference} and is a key to nothing: Safaricom echoes it nowhere and does
 *       not deduplicate on it.</li>
 *   <li>The only synchronous query demands that {@code CheckoutRequestID}, so a submission
 *       whose answer was lost cannot be queried at all: {@link #resolves()} is
 *       {@link Resolution#CALLBACK} alone (ADR 0014).</li>
 *   <li>The callback names nothing Nkap chose, so {@link #parseCallback} can only report the
 *       operator's own reference. The gateway attributes it by the address it composed.</li>
 * </ul>
 *
 * <p>No B2C: its authentication differs in kind and nothing about it has been observed.
 */
public final class MpesaAdapter implements ProviderAdapter {

    private static final ProviderId DEFAULT_ID = ProviderId.of("mpesa");
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int BRIEF_BODY = 200;

    /**
     * The {@code Password}'s {@code Timestamp}, {@code yyyyMMddHHmmss} — the format is
     * <strong>observed</strong>, from Safaricom's own documented example. That it is Nairobi
     * local time is <strong>modelled</strong>: the page records that {@code CheckoutRequestID}
     * encodes Nairobi time, not which zone the request's timestamp must be in.
     */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    /** Observed in Safaricom's documented example request. */
    private static final String TRANSACTION_TYPE = "CustomerPayBillOnline";

    /** The one error code observed to answer both an unknown and a known reference. */
    private static final String DISTINGUISHES_NOTHING = "500.001.1001";

    private final ProviderId id;
    private final MpesaProfile profile;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final MpesaTokenCache tokens;
    private final Clock clock;

    public MpesaAdapter(MpesaProfile profile) {
        this(DEFAULT_ID, profile, DEFAULT_REQUEST_TIMEOUT);
    }

    public MpesaAdapter(ProviderId id, MpesaProfile profile, Duration requestTimeout) {
        this(id, profile, requestTimeout, MpesaTokenCache.DEFAULT_REFRESH_MARGIN, Clock.systemUTC());
    }

    MpesaAdapter(ProviderId id, MpesaProfile profile, Duration requestTimeout, Duration tokenRefreshMargin,
                 Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.tokens = new MpesaTokenCache(profile, http, requestTimeout, json, tokenRefreshMargin);
    }

    @Override
    public ProviderId id() {
        return id;
    }

    /**
     * {@code COLLECT} only. No balance and no holder validation: nothing about either has been
     * observed, so this adapter declares neither and refuses both.
     */
    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.Operation.COLLECT);
    }

    /**
     * {@code CALLBACK} alone, and this is ADR 0014's whole point. {@code stkpushquery} demands
     * the {@code CheckoutRequestID} Safaricom minted, which arrived in the very answer a lost
     * submission lost; a query built from only the reference Nkap chose cannot be formed. The
     * callback can resolve it: the gateway composes a per-payment {@code CallBackURL}, and
     * Safaricom was observed to deliver to that exact path (2026-09-22).
     */
    @Override
    public Set<Resolution> resolves() {
        return Resolution.of(Resolution.CALLBACK);
    }

    /**
     * {@code POST /mpesa/stkpush/v1/processrequest}. {@code Acknowledged} with the
     * {@code CheckoutRequestID} as the provider reference — the only key a later query can use.
     *
     * <p>Three refusals come before any request is sent, as data, never as a call the operator
     * refuses (ADR 0013):
     * <ul>
     *   <li>a currency this profile does not settle in;</li>
     *   <li>an amount with a fractional shilling. Every observed {@code Amount} is a whole number
     *       sent as a string ({@code "1"}); whether Safaricom accepts a fraction is not recorded,
     *       so this adapter does not find out with a merchant's payment — <strong>modelled</strong>;</li>
     *   <li>no {@code callbackUrl} in {@link PaymentIntent#providerOptions()}. Every observed
     *       submission carries a {@code CallBackURL}, and the callback is this adapter's only way
     *       to resolve a lost submission, so a submission without one is not sent.</li>
     * </ul>
     */
    @Override
    public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(reference, "reference");
        if (intent.operation() != Capability.Operation.COLLECT) {
            // The gateway refuses this before calling an adapter (PaymentService.submit), so
            // reaching it means a caller ignored capabilities(): a programming error, not a
            // payment outcome (ADR 0013).
            throw new IllegalArgumentException("the M-Pesa adapter only performs COLLECT, not " + intent.operation());
        }
        if (intent.amount().currency() != profile.currency()) {
            return new SubmitResult.NotAttempted("payment is in " + intent.amount().currency()
                    + " but this M-Pesa profile settles in " + profile.currency());
        }
        long minorPerMajor = (long) Math.pow(10, intent.amount().currency().minorUnits());
        if (intent.amount().amount() % minorPerMajor != 0) {
            return new SubmitResult.NotAttempted("M-Pesa STK Push amounts have only been observed as whole "
                    + intent.amount().currency() + "; " + intent.amount() + " is not attempted");
        }
        String callbackUrl = intent.providerOptions().get("callbackUrl");
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return new SubmitResult.NotAttempted("no callbackUrl: every M-Pesa submission carries a CallBackURL, "
                    + "and its callback is this adapter's only way to resolve a lost submission");
        }

        String timestamp = timestamp();
        ObjectNode body = json.createObjectNode();
        // Types exactly as Safaricom's own documented example has them: BusinessShortCode a JSON
        // number, Amount and PartyB strings -- PartyB holding the same digits as
        // BusinessShortCode. Treating the two consistently would produce a request Safaricom's
        // example does not match (docs/providers/m-pesa.md).
        body.put("BusinessShortCode", Long.parseLong(profile.businessShortCode()));
        body.put("Password", password(timestamp));
        body.put("Timestamp", timestamp);
        body.put("TransactionType", TRANSACTION_TYPE);
        body.put("Amount", Long.toString(intent.amount().amount() / minorPerMajor));
        body.put("PartyA", intent.counterpartyMsisdn());
        body.put("PartyB", profile.businessShortCode());
        body.put("PhoneNumber", intent.counterpartyMsisdn());
        body.put("CallBackURL", callbackUrl);
        // Nkap's reference, for the record on Safaricom's side. It comes back in nothing and
        // Safaricom does not deduplicate on it, so nothing here relies on it.
        body.put("AccountReference", reference.toString());
        body.put("TransactionDesc", intent.payerMessage().isBlank() ? "Payment" : intent.payerMessage());

        HttpRequest.Builder request = HttpRequest.newBuilder(profile.endpoint("/mpesa/stkpush/v1/processrequest"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)));

        HttpResponse<String> response = sendAuthenticated(request);
        int code = response.statusCode();
        String answer = response.body();
        if (code == 200) {
            String checkoutRequestId = readTree(answer, "submission").path("CheckoutRequestID").asText("");
            if (checkoutRequestId.isBlank()) {
                // An answer that names no payment is not an acknowledgement this adapter can
                // stand behind: without the identity nothing can ever ask about it.
                throw new ProviderUnavailableException(
                        "M-Pesa accepted the submission but named no CheckoutRequestID: " + brief(answer));
            }
            return new SubmitResult.Acknowledged(PaymentState.SUBMITTED, checkoutRequestId, answer);
        }
        if (code == 400) {
            // Observed: 400.002.02 "Bad Request - Invalid CallBackURL" (2026-09-18). A 400 is
            // the operator refusing the request outright; no payment exists.
            JsonNode error = readError(answer);
            return new SubmitResult.Rejected(error.path("errorCode").asText(""),
                    error.path("errorMessage").asText(brief(answer)), answer == null ? "" : answer);
        }
        throw new ProviderUnavailableException("M-Pesa processrequest returned HTTP " + code + ": " + brief(answer));
    }

    /**
     * {@code POST /mpesa/stkpushquery/v1/query}, keyed by {@code subject.providerReference()}
     * — the {@code CheckoutRequestID} {@code submit} returned, handed over by the caller (ADR
     * 0008), never looked up.
     *
     * <p>When it is blank the request cannot be formed at all, and this raises
     * {@link ProviderUnavailableException}: no answer was obtained. It never returns a status for
     * a question it could not ask.
     *
     * <p>{@code 500.001.1001} raises the same. On 2026-09-18 it answered a reference Safaricom did
     * not recognise; on 2026-09-23 it answered four polls in nineteen for one that unquestionably
     * existed. It distinguishes nothing, so mapping it to a status would claim Safaricom said
     * something about the payment when it did not — ADR 0013's rule, one layer down
     * ({@code docs/providers/m-pesa.md}, "What this means for whoever writes the adapter").
     */
    @Override
    public ProviderStatus query(QuerySubject subject, Capability.Operation capability)
            throws ProviderUnavailableException {
        Objects.requireNonNull(subject, "subject");
        if (capability != Capability.Operation.COLLECT) {
            throw new IllegalArgumentException("the M-Pesa adapter only answers COLLECT queries, not " + capability);
        }
        String checkoutRequestId = subject.providerReference();
        if (checkoutRequestId.isBlank()) {
            throw new ProviderUnavailableException("no CheckoutRequestID for " + subject.reference()
                    + ": M-Pesa's query cannot be formed from the reference Nkap chose alone");
        }

        String timestamp = timestamp();
        ObjectNode body = json.createObjectNode();
        body.put("BusinessShortCode", Long.parseLong(profile.businessShortCode()));
        body.put("Password", password(timestamp));
        body.put("Timestamp", timestamp);
        body.put("CheckoutRequestID", checkoutRequestId);

        HttpRequest.Builder request = HttpRequest.newBuilder(profile.endpoint("/mpesa/stkpushquery/v1/query"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)));

        HttpResponse<String> response = sendAuthenticated(request);
        int code = response.statusCode();
        String answer = response.body();
        if (code == 200) {
            return statusFrom(readTree(answer, "query"), answer);
        }
        if (DISTINGUISHES_NOTHING.equals(readError(answer).path("errorCode").asText(""))) {
            throw new ProviderUnavailableException("M-Pesa answered " + DISTINGUISHES_NOTHING
                    + ", which distinguishes nothing about " + checkoutRequestId + ": no answer was obtained");
        }
        throw new ProviderUnavailableException("M-Pesa query returned HTTP " + code + ": " + brief(answer));
    }

    /**
     * The STK callback, as observed: {@code {"Body":{"stkCallback":{MerchantRequestID,
     * CheckoutRequestID, ResultCode, ResultDesc}}}}, {@code ResultCode} a JSON number. It names
     * nothing Nkap chose, under any name, and {@link RawCallback} carries no path, so this
     * returns {@link CallbackEvent#unattributed} with the {@code CheckoutRequestID}. It cannot do
     * better and does not pretend to: the gateway attributes the callback by the address it
     * composed.
     *
     * <p>Unsigned, as observed on both runs: anyone who learns the URL can post to it. That is
     * why a callback is a hint the gateway confirms with a query, never a settlement.
     */
    @Override
    public CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException {
        Objects.requireNonNull(callback, "callback");
        JsonNode node;
        try {
            node = json.readTree(callback.body());
        } catch (IOException e) {
            throw new UntrustedCallbackException("callback body is not JSON", e);
        }
        JsonNode stkCallback = node == null ? null : node.path("Body").path("stkCallback");
        if (stkCallback == null || !stkCallback.isObject()) {
            throw new UntrustedCallbackException("callback body has no Body.stkCallback");
        }
        String checkoutRequestId = stkCallback.path("CheckoutRequestID").asText("");
        if (checkoutRequestId.isBlank()) {
            throw new UntrustedCallbackException("callback names no CheckoutRequestID");
        }
        try {
            return CallbackEvent.unattributed(checkoutRequestId, statusFrom(stkCallback, callback.body()));
        } catch (ProviderUnavailableException unreadable) {
            throw new UntrustedCallbackException("callback carries no readable ResultCode");
        }
    }

    /** Not declared: nothing about M-Pesa's balance has been observed. Refused, never guessed. */
    @Override
    public Money balance(Capability.Operation capability, Currency currency) {
        throw new UnsupportedOperationException("the M-Pesa adapter does not declare BALANCE");
    }

    /** Not declared: nothing about M-Pesa's holder validation has been observed. Refused, never guessed. */
    @Override
    public HolderStatus validateHolder(Capability.Operation capability, String msisdn) {
        throw new UnsupportedOperationException("the M-Pesa adapter does not declare HOLDER_VALIDATION");
    }

    // --- HTTP ---------------------------------------------------------------------------

    /**
     * Adds the bearer token and sends. A {@code 401} despite a live token triggers exactly one
     * refresh and one retry with the same request, which is not a resend: a request refused for
     * its credential was never processed (ADR 0014, decision 4). What Safaricom answers an
     * expired token with is not recorded; a {@code 401} is <strong>modelled</strong>. A second
     * {@code 401} is {@link ProviderUnavailableException}.
     */
    private HttpResponse<String> sendAuthenticated(HttpRequest.Builder request) throws ProviderUnavailableException {
        request.setHeader("Authorization", "Bearer " + tokens.bearer());
        HttpResponse<String> response = send(request.build());
        if (response.statusCode() == 401) {
            tokens.invalidate();
            request.setHeader("Authorization", "Bearer " + tokens.bearer());
            response = send(request.build());
            if (response.statusCode() == 401) {
                throw new ProviderUnavailableException("M-Pesa rejected a freshly issued token");
            }
        }
        return response;
    }

    private HttpResponse<String> send(HttpRequest request) throws ProviderUnavailableException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ProviderUnavailableException("M-Pesa did not answer within " + requestTimeout, e);
        } catch (IOException e) {
            throw new ProviderUnavailableException("M-Pesa call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("interrupted waiting for M-Pesa", e);
        }
    }

    // --- bodies ------------------------------------------------------------------------

    /**
     * A {@code ResultCode}, read as a number or as a numeric string — its type on the query
     * channel is not recorded — mapped through {@link MpesaStatusMap}. The code is kept as the
     * provider status code; {@code ResultDesc} is kept as the failure reason for the record and
     * never read for meaning.
     */
    private static ProviderStatus statusFrom(JsonNode node, String raw) throws ProviderUnavailableException {
        JsonNode resultCode = node.path("ResultCode");
        int code;
        if (resultCode.canConvertToInt()) {
            code = resultCode.asInt();
        } else if (isIntegerText(resultCode)) {
            code = Integer.parseInt(resultCode.asText().strip());
        } else {
            throw new ProviderUnavailableException("M-Pesa answered without a readable ResultCode: " + brief(raw));
        }
        PaymentState state = MpesaStatusMap.stateFor(code);
        String description = node.path("ResultDesc").asText("");
        return new ProviderStatus(state, Integer.toString(code), "", null,
                state == PaymentState.FAILED ? description : "", raw);
    }

    private static boolean isIntegerText(JsonNode node) {
        return node.isTextual() && node.asText().strip().matches("-?\\d{1,9}");
    }

    private String timestamp() {
        return ZonedDateTime.now(clock).withZoneSameInstant(NAIROBI).format(TIMESTAMP);
    }

    /** {@code base64(BusinessShortCode + Passkey + Timestamp)} — observed, by decoding Safaricom's own example. */
    private String password(String timestamp) {
        String raw = profile.businessShortCode() + profile.passkey() + timestamp;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode readTree(String body, String what) throws ProviderUnavailableException {
        if (body == null || body.isBlank()) {
            throw new ProviderUnavailableException("M-Pesa " + what + " returned 200 with an empty body");
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new ProviderUnavailableException("M-Pesa " + what + " body was not JSON: " + brief(body), e);
        }
    }

    /** An error body, {@code {errorCode, errorMessage}} as observed — or an empty object when unreadable. */
    private JsonNode readError(String body) {
        try {
            JsonNode node = json.readTree(body == null || body.isBlank() ? "{}" : body);
            return node == null ? json.createObjectNode() : node;
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private String write(ObjectNode body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise an M-Pesa request body", e);
        }
    }

    private static String brief(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String trimmed = body.strip();
        return trimmed.length() <= BRIEF_BODY ? trimmed : trimmed.substring(0, BRIEF_BODY) + "…";
    }
}
