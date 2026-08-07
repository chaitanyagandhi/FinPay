package com.finpay.platform.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.finpay.platform.web.RequestCorrelation;

/**
 * Covers the logging-context lifecycle, including the cleanup that stops one request's id being
 * attributed to the next request on the same pooled thread.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter(0);

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    @DisplayName("makes the id visible to logging for the duration of the request")
    void populatesLoggingContextDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wallets/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> observed.set(MDC.get(RequestCorrelation.REQUEST_ID_MDC_KEY)));

        assertThat(observed.get()).isNotBlank();
        assertThat(response.getHeader(RequestCorrelation.REQUEST_ID_HEADER)).isEqualTo(observed.get());
        assertThat(request.getAttribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE))
                .isEqualTo(observed.get());
    }

    @Test
    @DisplayName("clears the logging context afterwards so a pooled thread carries nothing over")
    void clearsLoggingContextAfterRequest() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/wallets/me"),
                new MockHttpServletResponse(),
                (req, res) -> {});

        assertThat(MDC.get(RequestCorrelation.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("clears the logging context even when the request fails")
    void clearsLoggingContextWhenRequestFails() {
        assertThatThrownBy(() -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/v1/wallets/me"),
                        new MockHttpServletResponse(),
                        (req, res) -> {
                            throw new IllegalStateException("downstream blew up");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestCorrelation.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("adopts a well-formed inbound id")
    void adoptsInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wallets/me");
        request.addHeader(RequestCorrelation.REQUEST_ID_HEADER, "req-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader(RequestCorrelation.REQUEST_ID_HEADER)).isEqualTo("req-abc-123");
    }
}
