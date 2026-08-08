package com.finpay.auth.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.auth.entity.RefreshToken;
import com.finpay.auth.entity.RevokedToken;
import com.finpay.auth.entity.TokenRevocationReason;
import com.finpay.auth.repository.RevokedTokenRepository;

/**
 * Ends a session.
 *
 * <p>Two things have to happen, because there are two kinds of token and only one of them can be
 * withdrawn by deleting a row. The refresh token's family is revoked, which stops the session
 * being extended. The access token already in the caller's hands cannot be recalled - it is
 * self-contained and valid until it expires - so its {@code jti} goes on the denylist for the few
 * minutes it has left.
 *
 * <p>Logging out is deliberately idempotent and tells the caller nothing. An unknown refresh token
 * produces the same empty success as a real one: reporting "no such token" would make this
 * endpoint a way of asking whether a token is genuine, without needing to be able to use it.
 */
@Service
public class LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

    private final RefreshTokenService refreshTokenService;
    private final TokenRevoker revoker;
    private final RevokedTokenRepository revokedTokens;
    private final JwtDecoder jwtDecoder;
    private final TokenDenylist denylist;

    public LogoutService(
            RefreshTokenService refreshTokenService,
            TokenRevoker revoker,
            RevokedTokenRepository revokedTokens,
            JwtDecoder jwtDecoder,
            TokenDenylist denylist) {
        this.refreshTokenService = refreshTokenService;
        this.revoker = revoker;
        this.revokedTokens = revokedTokens;
        this.jwtDecoder = jwtDecoder;
        this.denylist = denylist;
    }

    /**
     * Revokes the session identified by a refresh token, and denylists the access token if one was
     * presented.
     *
     * @param refreshToken the token whose family ends here; an unknown value is not an error
     * @param bearerToken the caller's access token, or null when none was sent
     */
    @Transactional
    public void logout(String refreshToken, String bearerToken) {
        Optional<RefreshToken> stored = refreshTokenService.findByToken(refreshToken);

        if (stored.isPresent()) {
            // Revoking the family, not the single token, so the successor a client may already
            // hold dies with it.
            revoker.revokeFamily(stored.get().getFamilyId(), TokenRevocationReason.LOGOUT);
        } else {
            log.info("Logout presented an unknown refresh token; answering as though it succeeded");
        }

        denylist(bearerToken, stored.map(RefreshToken::getUserId).orElse(null));
    }

    /**
     * Records the access token's id so it stops being accepted before it expires.
     *
     * <p>The token is verified rather than merely parsed. Reading the {@code jti} out of an
     * unverified JWT would let anyone denylist anyone else's access token by handing over a
     * forgery - a signed-out victim, on demand, with no credentials needed.
     */
    private void denylist(String bearerToken, UUID userIdFromSession) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return;
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(bearerToken);
        } catch (JwtException e) {
            // An expired or forged token needs no denylisting: nothing will accept it anyway.
            log.info("Logout presented an access token that did not verify; nothing to denylist");
            return;
        }

        UUID jti;
        try {
            jti = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Access token carried a jti that is not a UUID; cannot denylist it");
            return;
        }

        Instant tokenExpiry = jwt.getExpiresAt() != null ? jwt.getExpiresAt() : Instant.now();

        if (revokedTokens.existsByJti(jti)) {
            // Logging out twice with the same token is not an error, and the unique constraint on
            // jti would otherwise turn the second call into a 500. The denylist is republished
            // anyway: the earlier attempt may have been the one that could not reach Redis.
            denylist.revoke(jti, tokenExpiry);
            return;
        }

        UUID userId = jwt.getSubject() != null ? UUID.fromString(jwt.getSubject()) : userIdFromSession;

        // The durable record first: it is the one an auditor reads and the one that survives a
        // Redis restart. Publishing to the denylist is what actually stops the token being
        // accepted, and is deliberately best effort.
        revokedTokens.save(new RevokedToken(jti, userId, TokenRevocationReason.LOGOUT, tokenExpiry));
        denylist.revoke(jti, tokenExpiry);
        log.info("Denylisted access token {} until {}", jti, tokenExpiry);
    }
}
