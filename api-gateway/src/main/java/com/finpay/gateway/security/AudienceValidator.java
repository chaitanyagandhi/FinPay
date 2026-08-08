package com.finpay.gateway.security;

import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token that was minted for somebody else.
 *
 * <p>Spring's default validators check expiry and, when configured, the issuer - but not the
 * audience. A verifier that skips it accepts any token signed by a key it trusts, so a token
 * issued for a different system sharing that key works here too. Checking it turns one
 * compromised integration back into one compromised integration.
 *
 * @param expected the audience this platform's tokens must carry
 */
public record AudienceValidator(String expected) implements OAuth2TokenValidator<Jwt> {

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();

        if (audiences != null && audiences.contains(expected)) {
            return OAuth2TokenValidatorResult.success();
        }

        // The description is for logs, not the caller: the platform envelope says only that
        // authentication is required.
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The token was not issued for this platform.", null));
    }
}
