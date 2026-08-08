package com.finpay.platform.web.identity;

import java.util.Set;
import java.util.UUID;

/**
 * Who the gateway says is calling.
 *
 * <p>A cross-cutting contract, not domain logic: the gateway writes these facts onto every request
 * it has authenticated, and every service reads them the same way. Restating the header names in
 * each service is how they drift, and a service reading the wrong header name does not fail - it
 * simply sees nobody.
 *
 * <p>This is trustworthy only because the gateway strips any inbound copy of these headers and no
 * service port is published. It is a statement the edge makes, not a credential the caller
 * presents; a service that becomes reachable directly must verify the token itself instead.
 *
 * @param userId the authenticated subject
 * @param roles what the caller is allowed to do, as granted at sign-in
 * @param tokenId the session's token id, for tying a log line back to one session
 */
public record CallerIdentity(UUID userId, Set<String> roles, String tokenId) {

    public CallerIdentity {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
