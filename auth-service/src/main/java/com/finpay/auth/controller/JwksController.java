package com.finpay.auth.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Publishes the public half of the signing key.
 *
 * <p>This is what lets every other service verify an access token without holding anything that
 * could mint one. It is deliberately public and unauthenticated: a public key is public, and a
 * verifier has to be able to fetch it before it has any credentials of its own.
 *
 * <p>Only the public parameters are exposed. Nimbus derives the public JWK from the key pair, so
 * there is no code path here that could serve the private exponent by mistake.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class JwksController {

    private final JWKSource<SecurityContext> jwkSource;

    public JwksController(JWKSource<SecurityContext> jwkSource) {
        this.jwkSource = jwkSource;
    }

    @GetMapping("/.well-known/jwks.json")
    @Operation(
            summary = "Public keys for verifying access tokens",
            description =
                    """
                    A JWK Set containing the public half of every key currently used to sign access \
                    tokens. Select the key whose "kid" matches the token header. Publishing more than \
                    one key is normal during rotation.""")
    public Map<String, Object> jwks() throws Exception {
        return new com.nimbusds.jose.jwk.JWKSet(jwkSource.get(
                        new com.nimbusds.jose.jwk.JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()),
                        null))
                .toJSONObject();
    }
}
