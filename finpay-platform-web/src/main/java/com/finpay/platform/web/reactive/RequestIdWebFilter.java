package com.finpay.platform.web.reactive;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.finpay.platform.web.RequestCorrelation;

import reactor.core.publisher.Mono;

/**
 * Establishes the request id at the edge and passes it to everything downstream.
 *
 * <p>Runs on the reactive stack, where a request is not bound to one thread and a thread-local
 * logging context cannot be relied on. The id is therefore carried explicitly: stored on the
 * exchange, added to the request headers the gateway forwards, echoed on the response, and placed in
 * the Reactor context for anything that needs it further along the chain.
 *
 * <p>Adding the header to the forwarded request is what makes the whole scheme work. Each downstream
 * service reads it in its own servlet filter and adopts the same id, so one identifier follows a
 * transfer across every service it touches.
 */
public class RequestIdWebFilter implements WebFilter, Ordered {

    private final int order;

    public RequestIdWebFilter(int order) {
        this.order = order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = RequestCorrelation.resolve(
                exchange.getRequest().getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER));

        ServerWebExchange withRequestId = exchange.mutate()
                .request(request ->
                        request.headers(headers -> headers.set(RequestCorrelation.REQUEST_ID_HEADER, requestId)))
                .build();

        withRequestId.getAttributes().put(RequestCorrelation.REQUEST_ID_ATTRIBUTE, requestId);
        withRequestId.getResponse().getHeaders().set(RequestCorrelation.REQUEST_ID_HEADER, requestId);

        return chain.filter(withRequestId)
                .contextWrite(context -> context.put(RequestCorrelation.REQUEST_ID_MDC_KEY, requestId));
    }

    @Override
    public int getOrder() {
        return order;
    }
}
