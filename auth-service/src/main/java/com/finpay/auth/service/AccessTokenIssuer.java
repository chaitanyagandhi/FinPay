package com.finpay.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.finpay.auth.config.JwtProperties;
import com.finpay.auth.entity.Role;
import com.finpay.auth.entity.User;

/**
 * Mints signed access tokens.
 *
 * <p>The token carries the minimum a service needs to make an authorization decision: who the
 * caller is and what they are allowed to do. It deliberately carries no email, no name and nothing
 * else identifying a person - a JWT is only base64, readable by anyone who intercepts it and by
 * every log that records an Authorization header.
 */
@Component
public class AccessTokenIssuer {

    /** Roles, as a claim name that is conventional enough for a verifier to expect. */
    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public AccessTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /**
     * Issues an access token for a user.
     *
     * @return the signed token, its identifier and the instant it expires
     */
    public AccessToken issue(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
        // A unique id per token, so a single token can be revoked before it expires without
        // invalidating every other token the same user holds.
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .audience(List.of(properties.getAudience()))
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(tokenId)
                .claim(
                        ROLES_CLAIM,
                        user.getRoles().stream().map(Role::name).sorted().toList())
                .build();

        // RS256 with the key id in the header, so a verifier can pick the right public key from
        // the JWKS set and rotation does not require coordinating a restart.
        JwsHeader header = JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();

        String value =
                jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AccessToken(value, tokenId, issuedAt, expiresAt);
    }

    /**
     * A freshly minted access token.
     *
     * @param value the signed, encoded JWT
     * @param tokenId the {@code jti}, recorded when the token is revoked
     */
    public record AccessToken(String value, String tokenId, Instant issuedAt, Instant expiresAt) {}
}
