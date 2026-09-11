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
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The MTN MoMo Disbursements adapter — money out, the mirror of {@link MtnCollectionsAdapter}.
 *
 * <p>Disbursements is a <strong>separate MTN product</strong>: its own subscription key, API
 * user and key, token endpoint ({@code /disbursement/token/}) and base path
 * ({@code /disbursement/v1_0/transfer}). So it is a whole adapter with its own
 * {@link MtnProfile} and its own {@link MtnTokenCache}, not a flag on the collections one.
 * The behaviour it translates is the same shape — a 202 with an empty body, a status GET,
 * a callback with {@code referenceId}/{@code status}/{@code reason} — and the same
 * conservatism: {@link MtnStatusMap} maps what MTN documents and everything else becomes
 * {@link PaymentState#UNKNOWN}, never an invented failure. A transfer that MTN refuses for
 * lack of funds comes back as an ordinary {@code FAILED} with the operator's code; this
 * gateway does not predict that, refuse it in advance, or hold a reserve — it records.
 *
 * <p>The HTTP plumbing is deliberately a copy of the collections adapter's rather than a
 * shared base class: that class passes the conformance kit and is the most-tested in the
 * repository, and a refactor to share code would put those tests at risk for no gain (issue
 * #62 plan). {@link MtnCollectionsAdapter#mtnAmount} and {@link MtnStatusMap} are already
 * package-shared and are reused.
 */
public final class MtnDisbursementsAdapter implements ProviderAdapter {

    static final String TOKEN_PATH = "/disbursement/token/";
    static final String TRANSFER_PATH = "/disbursement/v1_0/transfer";

    private static final ProviderId ID = ProviderId.of("mtn");
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int BRIEF_BODY = 200;

    private final MtnProfile profile;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final MtnTokenCache tokens;

    public MtnDisbursementsAdapter(MtnProfile profile) {
        this(profile, DEFAULT_REQUEST_TIMEOUT);
    }

    public MtnDisbursementsAdapter(MtnProfile profile, Duration requestTimeout) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.tokens = new MtnTokenCache(profile, TOKEN_PATH, http, requestTimeout, json);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.DISBURSE);
    }

    @Override
    public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(reference, "reference");
        if (intent.operation() != Capability.DISBURSE) {
            throw new IllegalArgumentException(
                    "the MTN disbursements adapter only performs DISBURSE, not " + intent.operation());
        }
        if (intent.amount().currency() != profile.currency()) {
            throw new IllegalArgumentException("payment is in " + intent.amount().currency()
                    + " but this MTN profile settles in " + profile.currency());
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(profile.endpoint(TRANSFER_PATH))
                .timeout(requestTimeout)
                .header("Ocp-Apim-Subscription-Key", profile.subscriptionKey())
                .header("X-Target-Environment", profile.targetEnvironment())
                .header("X-Reference-Id", reference.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(transferBody(intent, reference)));
        String callbackUrl = intent.providerOptions().get("callbackUrl");
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            request.header("X-Callback-Url", callbackUrl);
        }

        HttpResponse<String> response = sendAuthenticated(request);
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return SubmitResult.acknowledged("", "");
        }
        if (code == 409) {
            // The reference was already used: a previous attempt reached MTN. Idempotency
            // worked. Acknowledge and let query() settle it. Same leniency as collections.
            return SubmitResult.acknowledged("", response.body());
        }
        if (code == 400) {
            return rejected(response);
        }
        throw new ProviderUnavailableException(
                "MTN transfer returned HTTP " + code + ": " + brief(response.body()));
    }

    @Override
    public ProviderStatus query(ReferenceId reference, Capability capability) throws ProviderUnavailableException {
        Objects.requireNonNull(reference, "reference");
        if (capability != Capability.DISBURSE) {
            throw new IllegalArgumentException(
                    "the MTN disbursements adapter only answers DISBURSE queries, not " + capability);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(profile.endpoint(TRANSFER_PATH + "/" + reference))
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
            // RESOURCE_NOT_FOUND: MTN has never seen the reference. Nkap persists it before
            // calling, so this is "never arrived" or "not visible yet" — the reconciler
            // concludes after its window. Not a failure. (Same rule as collections.)
            String errorCode = codeIn(body);
            return ProviderStatus.unknown(errorCode.isBlank() ? "RESOURCE_NOT_FOUND" : errorCode, body);
        }
        throw new ProviderUnavailableException("MTN transfer query returned HTTP " + code + ": " + brief(body));
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
    public Money balance(Capability capability, Currency currency) throws ProviderUnavailableException {
        throw new UnsupportedOperationException(
                "balance is out of scope for the disbursements adapter; capabilities() does not advertise BALANCE");
    }

    // --- HTTP ------------------------------------------------------------------------

    private HttpResponse<String> sendAuthenticated(HttpRequest.Builder request) throws ProviderUnavailableException {
        request.setHeader("Authorization", "Bearer " + tokens.bearer());
        HttpResponse<String> response = send(request.build());
        if (response.statusCode() == 401) {
            tokens.invalidate();
            request.setHeader("Authorization", "Bearer " + tokens.bearer());
            response = send(request.build());
            if (response.statusCode() == 401) {
                throw new ProviderUnavailableException("MTN rejected a freshly issued disbursement token");
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

    // --- bodies -------------------------------------------------------------------

    private String transferBody(PaymentIntent intent, ReferenceId reference) {
        ObjectNode node = json.createObjectNode();
        node.put("amount", MtnCollectionsAdapter.mtnAmount(intent.amount()));
        node.put("currency", intent.amount().currency().name());
        node.put("externalId", reference.toString());
        // Disbursements name the counterparty 'payee', where collections say 'payer'.
        ObjectNode payee = node.putObject("payee");
        payee.put("partyIdType", "MSISDN");
        payee.put("partyId", intent.counterpartyMsisdn());
        node.put("payerMessage", intent.payerMessage());
        node.put("payeeNote", intent.payeeNote());
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise the transfer body", e);
        }
    }

    private ProviderStatus statusFrom(String body) throws ProviderUnavailableException {
        if (body == null || body.isBlank()) {
            throw new ProviderUnavailableException("MTN transfer query returned 200 with an empty body");
        }
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (IOException e) {
            throw new ProviderUnavailableException("MTN transfer query body was not JSON: " + brief(body), e);
        }
        String status = node.path("status").asText("");
        String reason = node.path("reason").asText("");
        String transactionId = node.path("financialTransactionId").asText("");
        PaymentState state = MtnStatusMap.stateFor(status, reason, "");
        String code = reason.isBlank() ? status : reason;
        return new ProviderStatus(state, code, transactionId, null, reason, body);
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
