package dev.nkap.server.auth;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires caller authentication into the MVC stack: the {@link CallerAuthInterceptor} on every
 * request except the ones that are unauthenticated by design, and the
 * {@link CallerArgumentResolver} so controllers can take {@code ApiCredential caller}.
 *
 * <p><strong>{@code /callbacks/**} is excluded</strong> — the operator sends no credential.
 * {@code /actuator/**} and {@code /error} are excluded too: the health check the compose
 * file polls carries no key, and {@code /error} is the container's own forward.
 */
@Configuration
class AuthWebConfig implements WebMvcConfigurer {

    private final ApiKeyStore keys;

    AuthWebConfig(ApiKeyStore keys) {
        this.keys = keys;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CallerAuthInterceptor(keys))
                .excludePathPatterns("/callbacks/**", "/actuator/**", "/error");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }
}
