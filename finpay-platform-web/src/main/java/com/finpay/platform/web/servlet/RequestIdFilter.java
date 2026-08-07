package com.finpay.platform.web.servlet;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import com.finpay.platform.web.RequestCorrelation;

/**
 * Puts the request id into the logging context for the life of a request.
 *
 * <p>Once in the MDC, every log line the request produces carries the id as a field in the
 * structured output, with no logging call having to mention it.
 *
 * <p>The id is also written to the response and stored as a request attribute, so the error handler
 * can quote the same value the caller sees.
 */
public class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    private final int order;

    public RequestIdFilter(int order) {
        this.order = order;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = RequestCorrelation.resolve(request.getHeader(RequestCorrelation.REQUEST_ID_HEADER));

        MDC.put(RequestCorrelation.REQUEST_ID_MDC_KEY, requestId);
        request.setAttribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(RequestCorrelation.REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused. Leaving the entry behind would stamp the next,
            // unrelated request with this one's id, which is worse than having no id at all.
            MDC.remove(RequestCorrelation.REQUEST_ID_MDC_KEY);
        }
    }

    @Override
    public int getOrder() {
        return order;
    }
}
