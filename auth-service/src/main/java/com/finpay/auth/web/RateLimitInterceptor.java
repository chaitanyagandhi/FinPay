package com.finpay.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.finpay.auth.exception.RateLimitExceededException;
import com.finpay.auth.service.AuthRateLimiter;

/**
 * Refuses a caller who is going too fast, before the handler runs.
 *
 * <p>An interceptor rather than a servlet filter, deliberately. A filter sits outside the
 * dispatcher, so an exception thrown there never reaches {@code GlobalExceptionHandler} and the
 * refusal would have to hand-write its own JSON - a second, drifting copy of the platform error
 * envelope. From here the exception is resolved normally and a throttled caller gets exactly the
 * same response shape as every other failure in the platform.
 *
 * <p>Counting happens before the handler, so a refused request costs no BCrypt comparison and no
 * database round trip. That is most of the point: the expensive work is what an attacker is trying
 * to make the service do.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final AuthRateLimiter rateLimiter;

    public RateLimitInterceptor(AuthRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Per endpoint, so exhausting the sign-in allowance does not also lock the caller out of
        // refreshing a session they legitimately hold.
        String bucket = request.getRequestURI();

        AuthRateLimiter.Decision decision = rateLimiter.check(bucket, ClientRequests.ipAddressOf(request));

        if (decision.allowed()) {
            return true;
        }

        // Set before throwing: the exception resolver renders a body but does not reset the
        // response, so headers written here survive onto the 429.
        response.setHeader(
                HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfter().toSeconds()));

        throw new RateLimitExceededException(decision.retryAfter());
    }
}
