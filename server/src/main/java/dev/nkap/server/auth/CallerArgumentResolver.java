package dev.nkap.server.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller method declare {@code ApiCredential caller} and receive the identity
 * {@link CallerAuthInterceptor} resolved for the request.
 *
 * <p>If the attribute is absent, the endpoint is reachable without going through the
 * interceptor — a wiring mistake — and this fails closed with the same 401 rather than
 * handing the controller a {@code null} caller.
 */
public final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ApiCredential.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object caller = webRequest.getAttribute(
                CallerAuthInterceptor.CALLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (caller instanceof ApiCredential credential) {
            return credential;
        }
        throw CallerAuthInterceptor.unauthenticated();
    }
}
