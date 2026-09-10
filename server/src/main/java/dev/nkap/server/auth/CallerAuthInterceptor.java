package dev.nkap.server.auth;

import dev.nkap.server.web.ApiException;
import dev.nkap.server.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the caller from {@code Authorization: Bearer <key>} on every request that reaches
 * it, and makes the {@link ApiCredential} available to the controllers as a request
 * attribute (read back by {@link CallerArgumentResolver}).
 *
 * <p>A missing, malformed or unknown key is <strong>401</strong>, {@code problem+json}, with
 * a body that is the same in every case — it never says whether the key exists, the same
 * reason an unknown callback reference is a 202 and not a 404.
 *
 * <p>{@code /callbacks/**} is not intercepted (see {@code WebConfig}): the operator sends no
 * credential we can verify, and that path is safe because it only triggers a confirming
 * query and believes nothing in the payload — the argument is in {@code SettlementService}.
 */
public final class CallerAuthInterceptor implements HandlerInterceptor {

    /** Where the resolved caller is stashed for the request. */
    public static final String CALLER_ATTRIBUTE = CallerAuthInterceptor.class.getName() + ".caller";

    private final ApiKeyStore keys;

    public CallerAuthInterceptor(ApiKeyStore keys) {
        this.keys = keys;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = ApiKeys.bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        ApiCredential caller = Optional.ofNullable(token)
                .flatMap(keys::authenticate)
                .orElseThrow(CallerAuthInterceptor::unauthenticated);
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        return true;
    }

    static ApiException unauthenticated() {
        // One sentence for a missing key and a wrong one alike. "Invalid key" would confirm
        // that valid keys have a different answer, which is an oracle.
        return new ApiException(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED,
                "Authentication required",
                "Provide a valid API key as 'Authorization: Bearer <key>'.");
    }
}
