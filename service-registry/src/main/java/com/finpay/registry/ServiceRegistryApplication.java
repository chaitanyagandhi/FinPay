package com.finpay.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Entry point for the FinPay service registry.
 *
 * <p>Runs a standalone Eureka server. Services register their instances here and resolve each other
 * by logical name, so an instance can move, scale out, or be replaced without any caller holding a
 * hard-coded address.
 *
 * <p>The registry deliberately does not depend on the config server. Discovery is the layer
 * everything else relies on to find anything at all, so making it wait for another service to be
 * reachable before it can start would turn one outage into two. Its own configuration is therefore
 * local, and it is not itself a config client.
 */
@SpringBootApplication
@EnableEurekaServer
public class ServiceRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceRegistryApplication.class, args);
    }
}
