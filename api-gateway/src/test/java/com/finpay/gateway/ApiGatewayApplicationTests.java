package com.finpay.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * Asserts the public route table: which paths are exposed and which service each reaches.
 *
 * <p>This is the gateway's contract with the outside world. A path silently gaining or losing a
 * route changes what the platform exposes, so the table is pinned here rather than left to
 * configuration drift.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
class ApiGatewayApplicationTests {

    @Autowired
    private RouteLocator routeLocator;

    private Map<String, Route> routesById;

    @BeforeEach
    void loadRoutes() {
        routesById = routeLocator.getRoutes().collectList().block().stream()
                .collect(Collectors.toMap(Route::getId, Function.identity()));
    }

    @Test
    @DisplayName("routes exactly the eight domain services and nothing else")
    void definesExpectedRoutes() {
        assertThat(routesById.keySet())
                .containsExactlyInAnyOrder(
                        "auth-service",
                        "user-service",
                        "wallet-service",
                        "transaction-service",
                        "payment-service",
                        "fraud-service",
                        "notification-service",
                        "audit-service");
    }

    @Test
    @DisplayName("resolves every route through the load balancer rather than a fixed address")
    void routesTargetLoadBalancedServiceIds() {
        assertThat(routesById.values())
                .allSatisfy(route -> assertThat(route.getUri().getScheme())
                        .as("route %s should resolve instances from the registry", route.getId())
                        .isEqualTo("lb"));

        // The service id in the URI must match the route id, or a route quietly points at the
        // wrong service.
        assertThat(routesById)
                .allSatisfy((id, route) -> assertThat(route.getUri().getHost()).isEqualTo(id));
    }

    @Test
    @DisplayName("exposes only /api/v1 paths, never a service-internal path")
    void exposesOnlyPublicApiPaths() {
        List<String> predicates = routesById.values().stream()
                .map(route -> route.getPredicate().toString())
                .toList();

        assertThat(predicates).allSatisfy(predicate -> assertThat(predicate).contains("/api/v1/"));
        assertThat(predicates).allSatisfy(predicate -> assertThat(predicate).doesNotContain("/internal"));
    }

    @Test
    @DisplayName("maps each documented path prefix to the service that owns it")
    void mapsDocumentedPathsToOwningServices() {
        assertRoutes("auth-service", "/api/v1/auth/**");
        assertRoutes("user-service", "/api/v1/users/**", "/api/v1/beneficiaries/**");
        assertRoutes("wallet-service", "/api/v1/wallets/**");
        assertRoutes("transaction-service", "/api/v1/transactions/**", "/api/v1/statements/**");
        assertRoutes("payment-service", "/api/v1/payments/**", "/api/v1/payment-requests/**");
        assertRoutes("fraud-service", "/api/v1/admin/fraud/**");
        assertRoutes("notification-service", "/api/v1/notifications/**", "/api/v1/notification-preferences/**");
        assertRoutes("audit-service", "/api/v1/admin/audit-events/**");
    }

    private void assertRoutes(String routeId, String... expectedPaths) {
        Route route = routesById.get(routeId);
        assertThat(route).as("route %s should be defined", routeId).isNotNull();

        String predicate = route.getPredicate().toString();
        for (String expectedPath : expectedPaths) {
            assertThat(predicate)
                    .as("route %s should match %s", routeId, expectedPath)
                    .contains(expectedPath);
        }
    }
}
