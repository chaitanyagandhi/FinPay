package com.finpay.auth.config;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * The key that signs access tokens.
 *
 * <p>RSA rather than a shared secret. With HMAC, every service that needs to <em>verify</em> a
 * token also holds the key that <em>mints</em> one, so a single compromised service can forge an
 * administrator's identity. With a key pair, only this service can sign; everything else needs
 * nothing but the public half, published at the JWKS endpoint.
 *
 * <p>In production the private key arrives from the environment. When none is configured a pair is
 * generated at startup, which keeps local development to a single command at the cost of
 * invalidating every issued token on restart - stated loudly in the log rather than left to be
 * discovered.
 */
@Configuration
@EnableConfigurationProperties({
    JwtProperties.class,
    RefreshTokenProperties.class,
    LockoutProperties.class,
    RateLimitProperties.class
})
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    private static final int KEY_SIZE = 2048;

    /**
     * The key pair, as a JWK set.
     *
     * <p>Each key carries a {@code kid}. Verifiers select by it, which is what makes key rotation
     * possible without a flag day: the old and new public keys can both be published while tokens
     * signed by either are still in flight.
     */
    @Bean
    public RSAKey signingKey(
            @Value("${finpay.auth.jwt.private-key:}") String privateKeyPem,
            @Value("${finpay.auth.jwt.public-key:}") String publicKeyPem,
            @Value("${finpay.auth.jwt.key-id:}") String configuredKeyId) {

        return StringUtils.hasText(privateKeyPem)
                ? fromConfiguration(privateKeyPem, publicKeyPem, configuredKeyId)
                : generated();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey signingKey) {
        return new ImmutableJWKSet<>(new JWKSet(signingKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Verifies access tokens that this service itself issued.
     *
     * <p>Needed by logout, which reads the {@code jti} of the token being withdrawn. The signature
     * is checked rather than the token merely parsed: accepting an unverified {@code jti} would
     * let anyone denylist anyone else's access token by presenting a forgery.
     *
     * <p>This is a decoder, not a filter chain. auth-service still has no Spring Security web
     * configuration, and its endpoints stay public - see the note on the security dependencies in
     * this module's POM.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey signingKey) throws JOSEException {
        return NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
    }

    private RSAKey fromConfiguration(String privateKeyPem, String publicKeyPem, String keyId) {
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey =
                    (RSAPrivateKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(decode(privateKeyPem)));
            RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(decode(publicKeyPem)));

            log.info("Signing access tokens with the configured RSA key");
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(
                            StringUtils.hasText(keyId)
                                    ? keyId
                                    : UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            // Starting with an unusable key would mean every login fails at runtime with an
            // error that says nothing about the cause. Refusing to start says it plainly.
            throw new IllegalStateException("The configured JWT signing key could not be read", e);
        }
    }

    private RSAKey generated() {
        log.warn("No JWT signing key configured; generating an ephemeral RSA key. "
                + "Every token issued becomes invalid when this service restarts, and a second "
                + "instance would sign with a different key. Configure finpay.auth.jwt.private-key "
                + "and .public-key outside local development.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();

            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate an RSA signing key", e);
        }
    }

    /** Accepts a base64 key with or without PEM armour, and tolerates embedded newlines. */
    private byte[] decode(String pem) {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
