package dev.nkap.provider.mtn;

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
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The MTN MoMo Collections adapter — the first implementation of {@link ProviderAdapter}.
 *
 * <p>It translates and nothing more. State transitions, idempotency and bookkeeping are
 * the core's job; this class turns one {@link PaymentIntent} into MTN's requesttopay call
 * and MTN's answers back into {@link SubmitResult} / {@link ProviderStatus}. Where it
 * cannot map an answer with confidence it says {@link PaymentState#UNKNOWN} or throws
 * {@link ProviderUnavailableException} — it never invents a failure.
 *
 * <p>One adapter, one {@link MtnProfile}. See ADR 0004 and {@code docs/providers/mtn.md}.
 */
public final class MtnCollectionsAdapter implements ProviderAdapter {

    private static final ProviderId ID = ProviderId.of("mtn");
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int BRIEF_BODY = 200;

    private final MtnProfile profile;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final MtnTokenCache tokens;

    public MtnCollectionsAdapter(MtnProfile profile) {
        this(profile, DEFAULT_REQUEST_TIMEOUT);
    }

    public MtnCollectionsAdapter(MtnProfile profile, Duration requestTimeout) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.tokens = new MtnTokenCache(profile, http, requestTimeout, json);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.Operation.COLLECT, Capability.Feature.BALANCE, Capability.Feature.HOLDER_VALIDATION);
    }

    @Override
    public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(reference, "reference");
        if (intent.operation() != Capability.Operation.COLLECT) {
            throw new IllegalArgumentException(
                    "the MTN collections adapter only performs COLLECT, not " + intent.operation());
        }
        if (intent.amount().currency() != profile.currency()) {
            throw new IllegalArgumentException("payment is in " + intent.amount().currency()
                    + " but this MTN profile settles in " + profile.currency());
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(profile.endpoint("/collection/v1_0/requesttopay"))
                .timeout(requestTimeout)
                .header("Ocp-Apim-Subscription-Key", profile.subscriptionKey())
                .header("X-Target-Environment", profile.targetEnvironment())
                .header("X-Reference-Id", reference.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(submitBody(intent, reference)));
        String callbackUrl = intent.providerOptions().get("callbackUrl");
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            request.header("X-Callback-Url", callbackUrl);
        }

        HttpResponse<String> response = sendAuthenticated(request);
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            // 202, and the body is empty by contract — do not try to read it.
            return SubmitResult.acknowledged("", "");
        }
        if (code == 409) {
            // The reference was already used, which — since Nkap persists it before
            // calling — means a previous attempt reached MTN. Idempotency worked and
            // nothing was duplicated. Not an error: acknowledge and let query() settle it.
            //
            // Kept deliberately lenient: any 409 on requesttopay is "already submitted".
            // Narrowing it to the RESOURCE_ALREADY_EXIST code alone is only safe once the
            // simulator returns MTN-shaped error bodies — issue #26. Doing it now would
            // make these tests pass against a fiction.
            return SubmitResult.acknowledged("", response.body());
        }
        if (code == 400) {
            return rejected(response);
        }
        throw new ProviderUnavailableException(
                "MTN requesttopay returned HTTP " + code + ": " + brief(response.body()));
    }

    @Override
    public ProviderStatus query(ReferenceId reference, Capability.Operation capability) throws ProviderUnavailableException {
        Objects.requireNonNull(reference, "reference");
        if (capability != Capability.Operation.COLLECT) {
            throw new IllegalArgumentException(
                    "the MTN collections adapter only answers COLLECT queries, not " + capability);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        profile.endpoint("/collection/v1_0/requesttopay/" + reference))
                .timeout(requestTimeout)
                .header("Ocp-Apim-Subscription-Key", profile.subscriptionKey())
                .header("X-Target-Environment", profile.targetEnvironment())
                .GET();

        HttpResponse<String> response = sendAuthenticated(request);
        int code = response.statusCode();
        String body = response.body();
        if (code == 200) {
            return statusFrom(body);
        }
        if (code == 404) {
            // RESOURCE_NOT_FOUND. Not a failure: Nkap persists the reference before the
            // call, so a 404 is "the submission never arrived" or "it is not visible yet",
            // and one response cannot tell them apart. The reconciler concludes, after its
            // window and repeated 404s. This is the least intuitive rule in the adapter.
            // (ADR 0004, Correction.)
            String errorCode = codeIn(body);
            return ProviderStatus.unknown(errorCode.isBlank() ? "RESOURCE_NOT_FOUND" : errorCode, body);
        }
        throw new ProviderUnavailableException("MTN query returned HTTP " + code + ": " + brief(body));
    }

    @Override
    public CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException {
        Objects.requireNonNull(callback, "callback");
        JsonNode node;
        try {
            node = json.readTree(callback.body());
        } catch (IOException e) {
            throw new UntrustedCallbackException("callback body is not JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new UntrustedCallbackException("callback body is not a JSON object");
        }

        String rawReference = firstNonBlank(
                node.path("referenceId").asText(""),
                node.path("externalId").asText(""));
        ReferenceId reference;
        try {
            reference = ReferenceId.of(rawReference);
        } catch (RuntimeException e) {
            throw new UntrustedCallbackException("callback carries no usable reference: '" + rawReference + "'");
        }

        String status = node.path("status").asText("");
        if (status.isBlank()) {
            throw new UntrustedCallbackException("callback carries no status");
        }
        String reason = node.path("reason").asText("");
        String transactionId = node.path("financialTransactionId").asText("");
        PaymentState state = MtnStatusMap.stateFor(status, reason, "");
        ProviderStatus providerStatus = new ProviderStatus(
                state, reason.isBlank() ? status : reason, transactionId, null, reason, callback.body());
        return new CallbackEvent(reference, providerStatus);
    }

    @Override
    public Money balance(Capability.Operation capability, Currency currency) throws ProviderUnavailableException {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(currency, "currency");
        if (capability != Capability.Operation.COLLECT) {
            throw new IllegalArgumentException(
                    "the MTN collections adapter only reports a COLLECT balance, not " + capability);
        }
        if (currency != profile.currency()) {
            throw new IllegalArgumentException("balance was asked in " + currency
                    + " but this MTN profile settles in " + profile.currency());
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(profile.endpoint("/collection/v1_0/account/balance"))
                .timeout(requestTimeout)
                .header("Ocp-Apim-Subscription-Key", profile.subscriptionKey())
                .header("X-Target-Environment", profile.targetEnvironment())
                .GET();

        HttpResponse<String> response = sendAuthenticated(request);
        int code = response.statusCode();
        String body = response.body();
        if (code != 200) {
            throw new ProviderUnavailableException("MTN balance returned HTTP " + code + ": " + brief(body));
        }
        return balanceFrom(json, body, currency);
    }

    @Override
    public HolderStatus validateHolder(Capability.Operation capability, String msisdn) throws ProviderUnavailableException {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(msisdn, "msisdn");
        if (capability != Capability.Operation.COLLECT) {
            throw new IllegalArgumentException(
                    "the MTN collections adapter only validates a holder for COLLECT, not " + capability);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(
                        profile.endpoint("/collection/v1_0/accountholder/msisdn/" + msisdn + "/active"))
                .timeout(requestTimeout)
                .header("Ocp-Apim-Subscription-Key", profile.subscriptionKey())
                .header("X-Target-Environment", profile.targetEnvironment())
                .GET();

        HttpResponse<String> response = sendAuthenticated(request);
        int code = response.statusCode();
        String body = response.body();
        if (code != 200) {
            throw new ProviderUnavailableException(
                    "MTN account holder check returned HTTP " + code + ": " + brief(body));
        }
        return holderStatusFrom(json, body);
    }

    // --- HTTP ---------------------------------------------------------------------------

    /**
     * Adds the bearer token and sends. A {@code 401} despite a live token triggers exactly
     * one refresh and one retry <em>with the same request</em> — same reference, same
     * body. A second {@code 401} is {@link ProviderUnavailableException}.
     */
    private HttpResponse<String> sendAuthenticated(HttpRequest.Builder request) throws ProviderUnavailableException {
        request.setHeader("Authorization", "Bearer " + tokens.bearer());
        HttpResponse<String> response = send(request.build());
        if (response.statusCode() == 401) {
            tokens.invalidate();
            request.setHeader("Authorization", "Bearer " + tokens.bearer());
            response = send(request.build());
            if (response.statusCode() == 401) {
                throw new ProviderUnavailableException("MTN rejected a freshly issued token");
            }
        }
        return response;
    }

    private HttpResponse<String> send(HttpRequest request) throws ProviderUnavailableException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ProviderUnavailableException("MTN did not answer within " + requestTimeout, e);
        } catch (IOException e) {
            throw new ProviderUnavailableException("MTN call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("interrupted waiting for MTN", e);
        }
    }

    // --- bodies ------------------------------------------------------------------------

    private String submitBody(PaymentIntent intent, ReferenceId reference) {
        ObjectNode node = json.createObjectNode();
        node.put("amount", mtnAmount(intent.amount()));
        node.put("currency", intent.amount().currency().name());
        node.put("externalId", reference.toString());
        ObjectNode payer = node.putObject("payer");
        payer.put("partyIdType", "MSISDN");
        payer.put("partyId", intent.counterpartyMsisdn());
        node.put("payerMessage", intent.payerMessage());
        node.put("payeeNote", intent.payeeNote());
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise the requesttopay body", e);
        }
    }

    private ProviderStatus statusFrom(String body) throws ProviderUnavailableException {
        if (body == null || body.isBlank()) {
            throw new ProviderUnavailableException("MTN query returned 200 with an empty body");
        }
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (IOException e) {
            throw new ProviderUnavailableException("MTN query body was not JSON: " + brief(body), e);
        }
        String status = node.path("status").asText("");
        String reason = node.path("reason").asText("");
        // financialTransactionId appears only once the payment settles — absent while pending.
        String transactionId = node.path("financialTransactionId").asText("");
        PaymentState state = MtnStatusMap.stateFor(status, reason, "");
        String code = reason.isBlank() ? status : reason;
        return new ProviderStatus(state, code, transactionId, null, reason, body);
    }

    /**
     * MTN's {@code availableBalance}/{@code currency} pair (documented, not observed — see
     * "Still unknown" in {@code docs/providers/mtn.md}), refused rather than coerced if the
     * reported currency is not {@code expectedCurrency}: that is not the balance that was
     * asked for. Shared with {@link MtnDisbursementsAdapter}, like {@link #mtnAmount}.
     */
    static Money balanceFrom(ObjectMapper json, String body, Currency expectedCurrency)
            throws ProviderUnavailableException {
        if (body == null || body.isBlank()) {
            throw new ProviderUnavailableException("MTN balance returned 200 with an empty body");
        }
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (IOException e) {
            throw new ProviderUnavailableException("MTN balance body was not JSON: " + brief(body), e);
        }
        String reportedCurrency = node.path("currency").asText("");
        if (!expectedCurrency.name().equals(reportedCurrency)) {
            throw new ProviderUnavailableException("MTN reported a balance in "
                    + (reportedCurrency.isBlank() ? "<no currency>" : reportedCurrency) + " but " + expectedCurrency
                    + " was asked for — refusing to treat it as that balance");
        }
        long minorUnits = minorUnitsFromMtnAmount(node.path("availableBalance").asText(""), expectedCurrency);
        return Money.of(minorUnits, expectedCurrency);
    }

    private SubmitResult.Rejected rejected(HttpResponse<String> response) {
        String body = response.body();
        String code = codeIn(body);
        String message = "";
        try {
            message = json.readTree(body == null || body.isBlank() ? "{}" : body).path("message").asText("");
        } catch (IOException ignored) {
            // fall through to the brief body
        }
        if (message.isBlank()) {
            message = brief(body);
        }
        return new SubmitResult.Rejected(code, message, body == null ? "" : body);
    }

    private String codeIn(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body).path("code").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    static String mtnAmount(Money money) {
        // MTN's amount is a decimal string in the major currency unit; Money is minor units.
        return BigDecimal.valueOf(money.amount(), money.currency().minorUnits()).toPlainString();
    }

    /**
     * The inverse of {@link #mtnAmount}, and the trap the plan for issue #72 named: MTN's
     * decimal string, in the major unit, converted to an exact count of minor units for
     * {@code currency} — never rounded. {@link RoundingMode#UNNECESSARY} is the whole
     * mechanism: it succeeds silently when the extra decimal places carry no information (a
     * trailing zero — {@code "1000.00"} for XAF, which has none, is exactly 1000), and it
     * throws the moment rounding would actually be needed (a nonzero digit past the
     * currency's own precision — {@code "50.105"} for EUR cannot become 5010 or 5011 without
     * a guess). A rounded balance is how a reconciliation drifts by a few cents a day and
     * nobody can say when it started; refusing it here is cheaper than finding that drift.
     */
    static long minorUnitsFromMtnAmount(String decimal, Currency currency) throws ProviderUnavailableException {
        if (decimal == null || decimal.isBlank()) {
            throw new ProviderUnavailableException("MTN balance carried no amount");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(decimal);
        } catch (NumberFormatException e) {
            throw new ProviderUnavailableException("MTN balance amount '" + decimal + "' is not a decimal number", e);
        }
        try {
            return parsed.setScale(currency.minorUnits(), RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
        } catch (ArithmeticException e) {
            throw new ProviderUnavailableException("MTN balance amount '" + decimal
                    + "' has more decimal places than " + currency + " allows (" + currency.minorUnits()
                    + ") — refusing to round it", e);
        }
    }

    /**
     * MTN's account-holder answer (documented, not observed — see "Still unknown" in
     * {@code docs/providers/mtn.md}): a {@code result} field this method reads as either a
     * JSON boolean or a {@code "true"}/{@code "false"} string, since which one real MTN sends
     * is unconfirmed. Anything else — absent, some other text, an unparseable body — is
     * {@link ProviderUnavailableException}, never {@link HolderStatus#INACTIVE}: a code this
     * adapter does not recognise says nothing about the account, the same conservatism
     * {@link MtnStatusMap} applies to a payment status.
     */
    static HolderStatus holderStatusFrom(ObjectMapper json, String body) throws ProviderUnavailableException {
        if (body == null || body.isBlank()) {
            throw new ProviderUnavailableException("MTN account holder check returned 200 with an empty body");
        }
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (IOException e) {
            throw new ProviderUnavailableException("MTN account holder body was not JSON: " + brief(body), e);
        }
        JsonNode result = node.path("result");
        if (result.isBoolean()) {
            return result.asBoolean() ? HolderStatus.ACTIVE : HolderStatus.INACTIVE;
        }
        String text = result.asText("");
        if ("true".equalsIgnoreCase(text)) {
            return HolderStatus.ACTIVE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return HolderStatus.INACTIVE;
        }
        throw new ProviderUnavailableException(
                "MTN account holder result was not a recognisable true/false: " + brief(body));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String brief(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String trimmed = body.strip();
        return trimmed.length() <= BRIEF_BODY ? trimmed : trimmed.substring(0, BRIEF_BODY) + "…";
    }
}
