package com.finpay.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.auth.config.RefreshTokenProperties;
import com.finpay.auth.dto.ClientContext;
import com.finpay.auth.entity.RefreshToken;
import com.finpay.auth.entity.TokenRevocationReason;
import com.finpay.auth.entity.User;
import com.finpay.auth.exception.InvalidRefreshTokenException;
import com.finpay.auth.repository.RefreshTokenRepository;
import com.finpay.auth.repository.UserRepository;

/**
 * Issues refresh tokens and exchanges them, one for one.
 *
 * <p>A refresh token here is an opaque random value, not a JWT. A signed refresh token would have
 * to be denylisted to be revocable, which means a database lookup on every use - the same lookup
 * this design does, with a signature to verify on top and a set of claims readable by anyone who
 * intercepts it. The authority of this token comes from the row, so the token can be nothing but a
 * pointer to one.
 *
 * <p>Rotation is unconditional: every exchange spends the presented token and returns a new one.
 * That is what makes theft detectable at all. If tokens were reusable, a stolen token would work
 * silently alongside the real one forever; because they are not, the thief and the victim cannot
 * both keep using the chain, and whichever of them presents the spent token exposes the theft.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /**
     * 256 bits of randomness, which is what makes the hashing choice below safe.
     *
     * <p>Sized so that guessing a valid token is not a strategy at any rate of attempts.
     */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final TokenRevoker revoker;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenProperties properties;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokens,
            UserRepository users,
            TokenRevoker revoker,
            AccessTokenIssuer accessTokenIssuer,
            RefreshTokenProperties properties) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.revoker = revoker;
        this.accessTokenIssuer = accessTokenIssuer;
        this.properties = properties;
    }

    /**
     * Starts a new family for a user who has just signed in.
     *
     * <p>Called from within the sign-in transaction, so the session and the record of the sign-in
     * commit together or not at all.
     */
    public IssuedRefreshToken startFamily(User user, ClientContext client) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(properties.getTtl());

        RefreshToken saved = refreshTokens.save(
                RefreshToken.startFamily(user.getId(), hash(token), expiresAt, client.ipAddress(), client.userAgent()));

        log.info("Started refresh token family {} for user {}", saved.getFamilyId(), user.getId());
        return new IssuedRefreshToken(token, saved, expiresAt);
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token.
     *
     * @throws InvalidRefreshTokenException for every failure, regardless of cause
     */
    @Transactional
    public RotationResult rotate(String presentedToken, ClientContext client) {
        RefreshToken stored = refreshTokens
                .findByTokenHash(hash(presentedToken))
                .orElseThrow(() -> {
                    // No row: either never issued or already purged. Nothing to revoke.
                    log.info("Refresh rejected: no such token");
                    return new InvalidRefreshTokenException();
                });

        if (stored.isRevoked()) {
            // Already dead, usually because its family was revoked by an earlier detection or a
            // logout. Presenting it again tells us nothing new.
            log.info("Refresh rejected: token {} is revoked", stored.getId());
            throw new InvalidRefreshTokenException();
        }

        if (!stored.getExpiresAt().isAfter(Instant.now())) {
            // Expiry alone is not evidence of theft, so the family is left alone: signing in again
            // is the normal remedy and should not invalidate the user's other sessions.
            log.info("Refresh rejected: token {} expired at {}", stored.getId(), stored.getExpiresAt());
            throw new InvalidRefreshTokenException();
        }

        // Spend the token and detect reuse in the same statement. A zero here means the token was
        // already spent - either presented twice, or presented concurrently by two callers. Both
        // are treated as theft: a correct client holds exactly one refresh token and replaces it
        // on every rotation, so it can never be the second caller. The cost of being wrong is that
        // a badly written client signs its user out; the cost of the opposite mistake is that a
        // stolen session runs indefinitely.
        if (refreshTokens.markUsed(stored.getId(), Instant.now()) == 0) {
            detectedReuse(stored);
        }

        User user = users.findById(stored.getUserId()).orElseThrow(() -> {
            // The account went away between issuing and refreshing.
            log.info("Refresh rejected: user {} no longer exists", stored.getUserId());
            return new InvalidRefreshTokenException();
        });

        String successorToken = generateToken();
        Instant expiresAt = Instant.now().plus(properties.getTtl());
        RefreshToken successor = refreshTokens.save(
                stored.successor(hash(successorToken), expiresAt, client.ipAddress(), client.userAgent()));

        AccessTokenIssuer.AccessToken accessToken = accessTokenIssuer.issue(user);
        log.info(
                "Rotated refresh token {} to {} in family {}",
                stored.getId(),
                successor.getId(),
                successor.getFamilyId());

        return new RotationResult(accessToken, new IssuedRefreshToken(successorToken, successor, expiresAt));
    }

    /** Finds a token by its value, for callers that need the row rather than an exchange. */
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByToken(String presentedToken) {
        return refreshTokens.findByTokenHash(hash(presentedToken));
    }

    /**
     * Handles a token presented after it was already spent.
     *
     * <p>Revoking only the presented token would leave the successor the thief has already
     * obtained working perfectly. The whole family goes, which ends the session for both parties -
     * the legitimate user is inconvenienced and has to sign in again, which is the correct
     * outcome, because at this point there is no way to tell which of the two callers is them.
     */
    private void detectedReuse(RefreshToken stored) {
        log.warn(
                "Refresh token reuse detected: token {} in family {} for user {} was presented after being spent; "
                        + "revoking the family",
                stored.getId(),
                stored.getFamilyId(),
                stored.getUserId());

        // In its own transaction: the exception below rolls this one back.
        revoker.revokeFamily(stored.getFamilyId(), TokenRevocationReason.REUSE_DETECTED);

        throw new InvalidRefreshTokenException();
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * SHA-256, hex encoded.
     *
     * <p>Deliberately not BCrypt, which is the right choice for passwords and the wrong one here,
     * for two reasons. A password is chosen by a person and therefore guessable, so hashing it
     * must be slow; this value is 256 bits of {@link SecureRandom} output, so there is no
     * dictionary to work through and slowness buys nothing. And BCrypt salts every hash
     * separately, which makes "find the row for this token" a scan of every row rather than one
     * hit on a unique index - a lookup cost that grows with the number of live sessions.
     */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; if it is missing, nothing here is safe.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * A refresh token as issued: the value goes to the client, the row stays here.
     *
     * @param value the only copy that ever leaves this service
     */
    public record IssuedRefreshToken(String value, RefreshToken stored, Instant expiresAt) {}

    /** The pair returned by a successful exchange. */
    public record RotationResult(AccessTokenIssuer.AccessToken accessToken, IssuedRefreshToken refreshToken) {}
}
