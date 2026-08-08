package com.finpay.gateway;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Mints access tokens the way auth-service does, for tests that need real ones.
 *
 * <p>A real key pair and real RS256 signatures, rather than a mocked decoder. The gateway's job
 * here is to verify signature, issuer, audience and expiry; mocking the decoder would remove
 * exactly the behaviour under test and leave a suite that passes with the validation deleted.
 *
 * <p>The public half is served as a JWK Set from the stubbed downstream, so the gateway fetches
 * its keys over HTTP exactly as it will in production.
 */
final class TestTokens {

    static final String ISSUER = "https://finpay.local/auth";
    static final String AUDIENCE = "finpay";
    static final String JWKS_PATH = "/api/v1/auth/.well-known/jwks.json";

    private final RSAKey signingKey;

    TestTokens() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();

            this.signingKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test signing key", e);
        }
    }

    /** The public half, in the format the gateway expects to fetch. */
    String jwkSetJson() {
        return new JWKSet(signingKey.toPublicJWK()).toString();
    }

    /** A token that should be accepted: correct issuer, audience, algorithm and expiry. */
    String valid() {
        return mint(builder().build());
    }

    /** A valid token carrying a known id, so a test can revoke exactly this one. */
    String withTokenId(String jti) {
        return mint(builder().jwtID(jti).build());
    }

    String forUser(String subject, List<String> roles) {
        return mint(builder().subject(subject).claim("roles", roles).build());
    }

    String expired() {
        return mint(builder()
                .issueTime(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
                .expirationTime(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .build());
    }

    String withAudience(String audience) {
        return mint(builder().audience(audience).build());
    }

    String withIssuer(String issuer) {
        return mint(builder().issuer(issuer).build());
    }

    /** Signed by a key the gateway has never seen: the forgery case. */
    String signedByAnotherKey() {
        return new TestTokens().valid();
    }

    private JWTClaimsSet.Builder builder() {
        Instant now = Instant.now();

        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("roles", List.of("USER"));
    }

    private String mint(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign a test token", e);
        }
    }
}
