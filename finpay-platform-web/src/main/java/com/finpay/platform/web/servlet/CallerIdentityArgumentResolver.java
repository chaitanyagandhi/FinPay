package com.finpay.platform.web.servlet;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.finpay.platform.web.error.FinPayException;
import com.finpay.platform.web.error.PlatformErrorCode;
import com.finpay.platform.web.identity.CallerIdentity;
import com.finpay.platform.web.identity.IdentityHeaders;

/**
 * Lets a controller declare {@link CallerIdentity} as a parameter and be handed the caller.
 *
 * <p>The alternative is every controller in every service reading three headers and parsing a UUID,
 * which is the sort of code that gets copied once and then diverges quietly.
 *
 * <p>A missing or unparseable {@code X-User-Id} raises {@link PlatformErrorCode#UNAUTHORIZED}
 * rather than yielding an anonymous caller. A service reached without one has been called from
 * somewhere other than the gateway, and guessing at the caller's identity is the one thing it must
 * not do.
 */
public class CallerIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallerIdentity.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String rawUserId = request != null ? request.getHeader(IdentityHeaders.USER_ID) : null;

        if (rawUserId == null || rawUserId.isBlank()) {
            throw new UnauthenticatedCallerException();
        }

        UUID userId;
        try {
            userId = UUID.fromString(rawUserId.trim());
        } catch (IllegalArgumentException e) {
            // The gateway writes a UUID. Anything else means this did not come from the gateway.
            throw new UnauthenticatedCallerException();
        }

        return new CallerIdentity(
                userId,
                rolesOf(request.getHeader(IdentityHeaders.USER_ROLES)),
                request.getHeader(IdentityHeaders.TOKEN_ID));
    }

    private Set<String> rolesOf(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Rendered by the shared handler as the platform's 401, like every other failure. */
    static class UnauthenticatedCallerException extends FinPayException {

        private static final long serialVersionUID = 1L;

        UnauthenticatedCallerException() {
            super(PlatformErrorCode.UNAUTHORIZED, "Authentication is required.");
        }
    }
}
