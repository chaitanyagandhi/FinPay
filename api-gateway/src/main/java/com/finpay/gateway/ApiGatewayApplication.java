package com.finpay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the FinPay API gateway.
 *
 * <p>The single address the outside world uses. Clients know one host; which services exist, how
 * many instances each has, and where they are running stays internal.
 *
 * <p>Routes are declared explicitly in {@code application.yml} rather than derived from the service
 * registry, so the public API surface is a deliberate decision instead of a side effect of a
 * deployment. Route targets are {@code lb://} URIs resolved against the registry, which is what lets
 * an instance move or scale without a route change.
 *
 * <p>Token validation, rate limiting, request-ID propagation and security headers are added in the
 * steps that introduce them. Today the gateway routes and refuses service-internal paths.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
