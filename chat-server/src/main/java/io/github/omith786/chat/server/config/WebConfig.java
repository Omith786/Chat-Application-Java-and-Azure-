package io.github.omith786.chat.server.config;

import io.github.omith786.chat.server.auth.BearerTokenInterceptor;
import io.github.omith786.chat.server.auth.SessionTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers bearer-token authentication for the REST API. */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final BearerTokenInterceptor bearerTokens;

    public WebConfig(SessionTokenService tokens) {
        this.bearerTokens = new BearerTokenInterceptor(tokens);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // POST /api/sessions is how a client gets a token in the first place.
        registry.addInterceptor(new SkipSessionCreation(bearerTokens)).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(bearerTokens);
    }

    private record SkipSessionCreation(BearerTokenInterceptor delegate)
            implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            boolean creatingSession = "POST".equals(request.getMethod())
                    && "/api/sessions".equals(request.getRequestURI().substring(request.getContextPath().length()));
            return creatingSession || delegate.preHandle(request, response, handler);
        }
    }
}
