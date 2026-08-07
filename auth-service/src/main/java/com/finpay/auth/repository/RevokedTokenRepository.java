package com.finpay.auth.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.finpay.auth.entity.RevokedToken;

/** Denylist of access token ids, consulted by whatever validates tokens. */
public interface RevokedTokenRepository extends JpaRepository<RevokedToken, UUID> {

    boolean existsByJti(UUID jti);

    /**
     * Drops rows for tokens that have expired on their own.
     *
     * <p>Nothing schedules this yet - the sweep belongs with the other background jobs in Phase 8.
     * It lives here now because the table's purpose depends on it: without a purge the denylist
     * grows without bound, and the column that makes purging possible would look decorative.
     */
    @Modifying
    @Query("delete from RevokedToken r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
