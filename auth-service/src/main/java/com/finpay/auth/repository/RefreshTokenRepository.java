package com.finpay.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.finpay.auth.entity.RefreshToken;
import com.finpay.auth.entity.TokenRevocationReason;

/** Data access for {@link RefreshToken}. Tokens are always looked up by hash, never by value. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    /**
     * Spends a token, but only if it has not already been spent.
     *
     * <p>The {@code usedAt is null} predicate is the whole point. Checking "is it used?" in Java
     * and then writing "it is used now" leaves a window in which two concurrent refreshes both
     * read an unused token and both succeed, which would hand out two live successors from one
     * token and silently defeat reuse detection. Letting the database decide makes exactly one of
     * them win.
     *
     * @return 1 if this call spent the token, 0 if someone else already had
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Revokes every token in a family that is not revoked already.
     *
     * <p>A statement rather than loaded entities: this runs on failure paths, from a transaction
     * of its own, where mutating managed entities would be discarded by the rollback that follows.
     * See {@code TokenRevoker}.
     *
     * @return how many tokens were revoked
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RefreshToken t
               set t.revokedAt = :now, t.revokedReason = :reason
             where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(
            @Param("familyId") UUID familyId, @Param("reason") TokenRevocationReason reason, @Param("now") Instant now);
}
