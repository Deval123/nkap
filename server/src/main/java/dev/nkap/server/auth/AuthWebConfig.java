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
 * {@code /error} is excluded too — it is the container's own forward.
 *
 * <p><strong>{@code /actuator/**} is still excluded</strong>, and confirmed to still need to
 * be: putting Actuator on its own port ({@code management.server.port}, application.yml)
 * does not put it beyond this interceptor's reach. Spring boots the management endpoints in
 * a <em>child</em> context of this one, and a child context sees its parent's beans — this
 * {@link WebMvcConfigurer} included — so without this exclusion, health and metrics would
 * both demand an API key on a port no scraper or health check ever sends one to. The reason
 * this exclusion exists changed — it used to keep those endpoints reachable on the public
 * port, now it keeps them reachable on the management one — but it did not become
 * unnecessary, which is worth writing down since the first draft of this comment assumed it
 * had.
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
