package com.finpay.platform.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import com.finpay.platform.web.RequestCorrelation;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Covers the reactive half: establishing the id and, above all, passing it downstream.
 */
class RequestIdWebFilterTest {

    private final RequestIdWebFilter filter = new RequestIdWebFilter(0);

    @Test
    @DisplayName("generates an id and echoes it on the response when the caller sent none")
    void generatesWhenAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me"));

        StepVerifier.create(filter.filter(exchange, anyExchange -> Mono.empty()))
                .verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER);
        assertThat(responseId).isNotBlank();
        assertThat(UUID.fromString(responseId)).isNotNull();
    }

    @Test
    @DisplayName("forwards the id to the downstream service, which is what makes tracing work")
    void forwardsIdDownstream() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me"));
        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, forwarded -> {
                    forwardedHeaders.set(forwarded.getRequest().getHeaders());
                    return Mono.empty();
                }))
                .verifyComplete();

        String forwardedId = forwardedHeaders.get().getFirst(RequestCorrelation.REQUEST_ID_HEADER);
        assertThat(forwardedId)
                .as("the downstream service must receive the same id the caller is given")
                .isNotBlank()
                .isEqualTo(exchange.getResponse().getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("adopts a well-formed inbound id rather than minting a second one")
    void adoptsInboundId() {
        String inbound = "req-abc-123";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/wallets/me").header(RequestCorrelation.REQUEST_ID_HEADER, inbound));

        StepVerifier.create(filter.filter(exchange, anyExchange -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER))
                .isEqualTo(inbound);
    }

    @Test
    @DisplayName("replaces an inbound id that could forge log entries")
    void replacesMaliciousInboundId() {
        String inbound = "req\r\nlevel=ERROR";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/wallets/me").header(RequestCorrelation.REQUEST_ID_HEADER, inbound));

        StepVerifier.create(filter.filter(exchange, anyExchange -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER))
                .isNotEqualTo(inbound);
    }

    @Test
    @DisplayName("stores the id on the exchange so later filters can read it")
    void storesIdOnExchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me"));
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, forwarded -> {
                    seen.set(forwarded);
                    return Mono.empty();
                }))
                .verifyComplete();

        assertThat(seen.get().getAttributes()).containsKey(RequestCorrelation.REQUEST_ID_ATTRIBUTE);
    }
}
