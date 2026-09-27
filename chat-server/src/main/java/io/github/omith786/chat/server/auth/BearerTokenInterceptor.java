package io.github.omith786.chat.server.auth;

import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.server.web.ChatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires a valid {@code Authorization: Bearer <token>} header on the REST API and exposes the
 * username to controllers through {@link CurrentUser}. Registered for {@code /api/**} except
 * session creation (see {@code WebConfig}).
 */
public class BearerTokenInterceptor implements HandlerInterceptor, HandlerMethodArgumentResolver {

    static final String USER_ATTRIBUTE = BearerTokenInterceptor.class.getName() + ".user";
    private static final String BEARER = "Bearer ";

    private final SessionTokenService tokens;

    public BearerTokenInterceptor(SessionTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new ChatException(ErrorCode.UNAUTHORISED, "Missing bearer token");
        }
        String username = tokens.verify(header.substring(BEARER.length()).strip())
                .orElseThrow(() -> new ChatException(ErrorCode.UNAUTHORISED, "Invalid or expired session token"));
        request.setAttribute(USER_ATTRIBUTE, username);
        return true;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object user = webRequest.getAttribute(USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (user == null) {
            // Only reachable if a controller using @CurrentUser is mapped outside /api/**.
            throw new ChatException(ErrorCode.UNAUTHORISED, "Not authenticated");
        }
        return user;
    }
}
