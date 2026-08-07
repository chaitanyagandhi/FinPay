package com.finpay.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.auth.entity.TokenRevocationReason;
import com.finpay.auth.repository.RefreshTokenRepository;

/**
 * Revokes a token family in a transaction of its own.
 *
 * <p>This exists for the same reason {@link LoginAttemptRecorder} does, and the failure it
 * prevents is worse. Reuse detection ends by throwing, which rolls the caller's transaction back -
 * so a revocation written in that transaction would be erased by the very rejection it was
 * triggered by. The stolen token's family would stay live, the theft would be detected on every
 * subsequent attempt and acted on in none of them, and the security control would appear to work
 * while doing nothing at all.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this one independently, so
 * the revocation outlives the rejection.
 *
 * <p>Any write on a failure path in this service needs the same treatment.
 */
@Service
public class TokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(TokenRevoker.class);

    private final RefreshTokenRepository refreshTokens;

    public TokenRevoker(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /**
     * Revokes every unrevoked token in a family.
     *
     * @return how many tokens were revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, TokenRevocationReason reason) {
        int revoked = refreshTokens.revokeFamily(familyId, reason, Instant.now());
        log.info("Revoked {} refresh token(s) in family {}: reason={}", revoked, familyId, reason);
        return revoked;
    }
}
